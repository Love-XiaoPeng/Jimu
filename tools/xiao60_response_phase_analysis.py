"""Read retained phase marks; never execute or retry a product transaction."""
import argparse
import hashlib
import json
from pathlib import Path


def analyze(root):
    result = {}
    for tag in ('old', 'new'):
        outcome_file = root / ('trace-' + tag + '-success-outcome.json')
        if not outcome_file.exists():
            result[tag] = {'missingOutcome': True}
            continue
        outcome = json.loads(outcome_file.read_text())
        origin = outcome['timelineOriginNanos']
        deadline = outcome['absoluteDeadlineNanos']
        phases = []
        for line in (root / ('phases-' + tag) / 'phases.tsv').read_text().splitlines():
            nanos, thread, index, label = line.split('\t', 3)
            nanos = int(nanos)
            phases.append({'micros': (nanos-origin)//1000, 'remainingMicros': (deadline-nanos)//1000,
                           'thread': int(thread), 'index': int(index), 'phase': label})
        active = [row for row in phases if row['micros'] >= 0]
        stack = []
        durations = []
        for row in active:
            kind, _, name = row['phase'].partition(' ')
            if kind == 'ENTER' and '(' in name:
                stack.append(row)
            elif kind in ('RETURN', 'THROW') and '(' in name:
                entry = next((prior for prior in reversed(stack)
                              if prior['thread'] == row['thread'] and prior['phase'][6:] == name), None)
                if entry:
                    durations.append({'method': name, 'enterMicros': entry['micros'],
                                      'exitMicros': row['micros'], 'exit': kind,
                                      'durationMicros': row['micros'] - entry['micros']})
                    stack.remove(entry)
        failures = [row for row in active if row['phase'].startswith('THROW SQL ')]
        catches = [row for row in active if row['phase'] == 'CATCH driver.modelResponded']
        interval = None
        if failures and catches:
            start, end = failures[-1], catches[0]
            interval = {'sqlFailureToDriverCatchMicros': end['micros'] - start['micros'],
                        'start': start, 'end': end,
                        'stages': [row for row in active if start['micros'] <= row['micros'] <= end['micros']]}
        result[tag] = {'production': outcome['production'], 'outerTransaction': outcome['outerTransaction'],
                       'productionEndMicros': outcome['productionEndMicros'],
                       'primary': outcome['primary'], 'transactionException': outcome.get('transactionException'),
                       'failureInterval': interval, 'phases': active,
                       'methodDurations': durations,
                       'syntheticAccountFaults': outcome.get('syntheticAccountFaults', 0),
                       'phaseCount': len(phases),
                       'scope': 'Observed sample only; no claim of stable/cold success, persistent commit or UNKNOWN recovery.'}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    analysis = analyze(args.root)
    target = args.root / 'phase-analysis.json'
    target.write_text(json.dumps(analysis, ensure_ascii=False, indent=2))
    print(json.dumps({tag: {key: value for key, value in row.items()
                           if key not in ('primary', 'transactionException', 'phases')}
                      for tag, row in analysis.items()}, ensure_ascii=False, indent=2))
    print('analysisSHA256', hashlib.sha256(target.read_bytes()).hexdigest())
