"""Offline exact-product and cold-translator evidence analysis. Never executes a trial."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile
from xiao60_response_phase_analysis import analyze


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def inspect(root, old, new, repo):
    result = {'products': {}, 'rounds': {}, 'classes': [], 'productionChanged': False}
    exact = {}
    for tag, file in [('old', old), ('new', new)]:
        with zipfile.ZipFile(file) as jar:
            exact[tag] = {n.removeprefix('BOOT-INF/classes/'): jar.read(n) for n in jar.namelist()
                          if n.startswith('BOOT-INF/classes/') and not n.endswith('/')}
            count = len(exact[tag])
            for name in jar.namelist():
                if name.startswith('BOOT-INF/lib/') and any(key in name for key in
                        ('spring-jdbc-', 'spring-tx-', 'spring-beans-', 'spring-core-', 'mybatis-spring-')):
                    with zipfile.ZipFile(io.BytesIO(jar.read(name))) as lib:
                        exact[tag].update({n: lib.read(n) for n in lib.namelist() if n.endswith('.class')})
        result['products'][tag] = {'sha256': sha(file.read_bytes()), 'bytes': file.stat().st_size,
                                   'productionEntries': count}
    baseline = '0a8faa08b9089d4425b3ecd5a95b4f95c98e5727'
    subprocess.run(['git', '-C', str(repo), 'diff', '--exit-code', baseline, '--', 'src/main'], check=True)
    names = subprocess.check_output(['git', '-C', str(repo), 'ls-tree', '-r', '--name-only', baseline,
                                    '--', 'src/main/resources', 'tools/evaluation/frozen']).decode().splitlines()
    batch = subprocess.run(['git', '-C', str(repo), 'cat-file', '--batch'],
                           input=''.join(baseline + ':' + n + '\n' for n in names).encode(),
                           capture_output=True, check=True).stdout
    offset = 0
    for name in names:
        end = batch.index(b'\n', offset)
        size = int(batch[offset:end].split()[-1]); offset = end + 1
        assert batch[offset:offset+size] == (repo/name).read_bytes()
        offset += size + 1
    result['unchangedResources'] = len(names)
    for run in ('run1', 'run3'):
        group = root/run/'scope-three'
        phases = analyze(group)
        (group/'phase-analysis.json').write_text(json.dumps(phases, ensure_ascii=False, indent=2))
        result['rounds'][run] = {'cleanup': json.loads((group/'cleanup.json').read_text()), 'sides': {}}
        for tag in ('old', 'new'):
            row = phases[tag]
            active = row['phases']
            entry = next(p for p in active if p['phase'] == 'ENTER driver.modelResponded')
            logs = (group/('response-'+tag+'.log')).read_text().splitlines()
            restored = 0
            for line in logs:
                if line.startswith('ROLLBACK '):
                    facts = json.loads(line[9:]); assert facts['before'] == facts['after']; restored += 1
            result['rounds'][run]['sides'][tag] = {
                'production': row['production'], 'outerTransaction': row['outerTransaction'],
                'productionEndMicros': row['productionEndMicros'],
                'driverEntryMicrosIncludingQueryView': entry['micros'],
                'remainingAtDriverEntryMicros': entry['remainingMicros'],
                'primary': row['primary'], 'transactionException': row['transactionException'],
                'restorations': restored, 'RESULTrows': sum(s.startswith('RESULT ') for s in logs),
                'failureInterval': row['failureInterval'], 'durations': row['methodDurations']}
            for folder in sorted((group/('phases-'+tag)/'classes').iterdir()):
                name = (folder/'name.txt').read_text()+'.class'
                raw = (folder/'original.class').read_bytes()
                assert raw == exact[tag][name], (run, tag, name)
                result['classes'].append({'run': run, 'tag': tag, 'name': name, 'sha256': sha(raw)})
        worker = (group/'worker-original-response.json').read_bytes()
        assert worker == (group/'pending-response.json').read_bytes()
        result['rounds'][run]['workerResponse'] = {
            'bytes': len(worker), 'sha256': sha(worker), 'driverRawEqual': True,
            'sampleValueEqual': json.loads(worker) == json.loads((group/'fixture-sample-response.json').read_bytes()),
            'sampleRawEqual': worker == (group/'fixture-sample-response.json').read_bytes()}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--old', type=Path, required=True)
    parser.add_argument('--new', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path('Jimu'))
    args = parser.parse_args()
    result = inspect(args.root, args.old, args.new, args.repo)
    (args.root/'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({'products': result['products'], 'unchangedResources': result['unchangedResources'],
                      'exactOriginalClasses': len(result['classes']),
                      'outcomes': {run: {tag: {k: v for k, v in side.items() if k in
                          ('production', 'outerTransaction', 'productionEndMicros', 'restorations', 'driverEntryMicrosIncludingQueryView')}
                          for tag, side in row['sides'].items()} for run, row in result['rounds'].items()}}, indent=2))
