"""Review R1/R2: three existing normal slices plus same-connection full-row projection differential.

No old full matrix, UNKNOWN replay, shared services or provider calls outside loopback fixtures.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import signal
import subprocess
import traceback

from user_question_verify import QuestionsExperiment, batch
from todo_verify import TodoExperiment
from working_directory_verify import DirectoryExperiment
from session_trace_verify import TraceExperiment
from p4_image_verify import ImageExperiment, png


class QuestionSlice(QuestionsExperiment):
    def normal(self):
        task = self.task('review-question', [batch(False)])
        question = self.question(task)
        self.state(task, 'WAITING_USER')
        self.stopped_slot(task)
        status, receipt = self.respond(task, question, selected=['A'], key='review-answer')
        assert status == 202, receipt
        self.state(task, 'SUCCEEDED')
        status, replay = self.respond(task, question, selected=['A'], key='review-answer')
        self.assert_that(status == 200 and replay == receipt,
                         'public question prepare/finish and original answer receipt replay')


class SessionSlice(TraceExperiment):
    def normal(self):
        task = self.task('review-fix-session', [])
        worker = self.start('review-session-worker', 'worker')
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        self.assert_that(int(self.sql("SELECT COUNT(*) FROM step WHERE task_id='" + task + "';")) > 0,
                         'public prepare produces session step and attempt facts')


class MediaSlice(ImageExperiment):
    def normal(self):
        body = {'goal': 'image-call', 'capabilityIds': ['read', 'read_image', 'compose_report', 'present'],
                'images': [{'filePath': 'inputs/images/sample.png', 'dataBase64': base64.b64encode(png()).decode()}]}
        status, view = self.http('POST', '/api/tasks', body, key='review-image-call')
        assert status == 202, view
        task = view['taskId']
        worker = self.start('review-media-worker', 'worker')
        self.state(task, 'SUCCEEDED')
        self.stop(worker)
        self.assert_that(self.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + task + "' AND category='MEDIA_CHECKPOINT' AND state='SETTLED' AND actual=amount;") != '0',
                         'real media checkpoints settle actual=amount')
        status, view = self.http('POST', '/api/tasks', body, key='review-image-budget')
        assert status == 202, view
        limited = view['taskId']
        original_limit = self.sql("SELECT storage_limit FROM tenant_budget WHERE tenant_id='tenant-a';")
        provider = len(self.fixture.requests)
        # Legal private quota exhaustion after accepting media, before checkpoint preparation.
        self.sql("UPDATE tenant_budget SET storage_limit=storage_held+media_checkpoint_held WHERE tenant_id='tenant-a';")
        try:
            worker = self.start('review-media-limit-worker', 'worker')
            rejected = self.state(limited, 'FAILED')
            self.stop(worker)
            self.assert_that(rejected['code'] == 'STORAGE_BUDGET_EXCEEDED'
                             and len(self.fixture.requests) == provider
                             and self.sql("SELECT COUNT(*) FROM reservation WHERE task_id='" + limited + "' AND category='MEDIA_CHECKPOINT';") == '0',
                             'media checkpoint budget rejection has no reservation or provider consumption')
        finally:
            self.sql("UPDATE tenant_budget SET storage_limit=" + original_limit + " WHERE tenant_id='tenant-a';")


def verify(args):
    repo = Path(__file__).resolve().parents[1]
    classes = args.evidence / 'executed' / 'classes'
    classes.mkdir(parents=True)
    source = repo / 'src/test/java/io/github/lovexiaopeng/jimu/adapters/persistence/ReviewProjectionScenario.java'
    shutil.copyfile(source, classes.parent / source.name)
    for file in (repo / 'target/test-classes').rglob('ReviewProjectionScenario*.class'):
        target = classes / file.relative_to(repo / 'target/test-classes')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, target)
    assert list(classes.rglob('*.class'))
    products = {}
    for role, jar in [('old-rebuilt', args.old_jar), ('new', args.jar)]:
        products[role] = {'bytes': jar.stat().st_size, 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
    (classes.parent / 'products.json').write_text(json.dumps(products, indent=2))
    failures = []
    for label, kind in [('question', QuestionSlice), ('todo', TodoExperiment), ('directory', DirectoryExperiment), ('session', SessionSlice), ('media', MediaSlice)]:
        if args.slices and label not in args.slices.split(','):
            continue
        experiment = kind(args.jar, args.evidence / label)
        try:
            experiment.prepare()
            experiment.normal()
            # All requested work is finished before the read-only differential; stop worker polling.
            for child in experiment.children:
                if child is not experiment.control['process'] and child.poll() is None:
                    experiment.stop({'process': child, 'name': 'before-projection'})
            provider = len(experiment.fixture.requests)
            config = experiment.control['folder'] / 'runtime.properties'
            for role, jar in [('old-rebuilt', args.old_jar), ('new', experiment.jar)]:
                local = experiment.root / (role + '-projection.jar')
                shutil.copyfile(jar, local)
                command = [experiment.java, '-Xmx256m',
                    '-Dloader.main=io.github.lovexiaopeng.jimu.adapters.persistence.ReviewProjectionScenario',
                    '-Dloader.path=' + str(classes), '-cp', str(local),
                    'org.springframework.boot.loader.launch.PropertiesLauncher', str(config),
                    str(repo / 'docs/verification/XIAO-60/evidence/review-fix-projections.json'),
                    str(repo / 'docs/verification/XIAO-60/evidence/review-fix-schema22-columns.json')]
                logfile = experiment.evidence / (role + '-projections.log')
                with logfile.open('w') as log:
                    result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=150)
                assert result.returncode == 0, f'{role} projections failed: {logfile}'
                assert 'PASS projections=17 wrappers=6' in logfile.read_text()
            assert len(experiment.fixture.requests) == provider, 'projections cannot invoke provider'
            before = (experiment.evidence / 'old-rebuilt-projections.log').read_text().splitlines()
            after = (experiment.evidence / 'new-projections.log').read_text().splitlines()
            # Connection IDs are per-JVM; all original typed raw rows remain exact.
            def facts(lines):
                values = [json.loads(line.split(' ', 1)[1]) for line in lines
                          if line.startswith(('PROJECTION ', 'WRAPPER '))]
                return sorted(values, key=lambda value: value.get('id', value.get('table')))
            assert facts(before) == facts(after), 'old/new full typed rows differ'
            experiment.assert_that(True, 'old/new 17 Mapper and 6 Wrapper full typed projections match')
        except BaseException:
            (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
            failures.append(label)
        finally:
            experiment.cleanup()
    assert not failures, 'preserved failed slices: ' + str(failures)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground termination')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--old-jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--slices', help='Only retry named affected slices; preserve earlier evidence.')
    verify(parser.parse_args())
