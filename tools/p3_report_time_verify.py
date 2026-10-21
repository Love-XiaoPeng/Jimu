"""Report UTC contract on owned MySQL sessions and local model/TLS substitutes only."""
import concurrent.futures
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import sys

sys.dont_write_bytecode = True

from p3_business_verify import BusinessExperiment, EVIDENCE, SOURCE, report_draft
from p3_report_verify import ReportModel


class TimeModel(ReportModel):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        system = next(m['content'] for m in body['messages'] if m['role'] == 'system')
        clock = re.search(r'report time (\S+)\.', system).group(1)
        self.server.requests.append({'goal': goal, 'reportTime': clock})
        replies = [m for m in body['messages'] if m['role'] == 'tool']
        stages = [('web_fetch', {'url': SOURCE}), ('read', {'file_path': 'report.md'}),
                  ('compose_report', {'file_path': 'report.md', 'draft': report_draft(goal)}),
                  ('present', {'files': [{'path': 'report.md'}]})]
        message = {'role': 'assistant', 'content': 'local report complete'}
        if len(replies) < len(stages):
            name, arguments = stages[len(replies)]
            message['tool_calls'] = [{'id': 'clock_' + str(len(replies)), 'type': 'function',
                                     'function': {'name': name, 'arguments': json.dumps(arguments)}}]
        self.send(200, {'id': 'local-clock', 'choices': [{'index': 0, 'message': message,
            'finish_reason': 'tool_calls' if 'tool_calls' in message else 'stop'}],
            'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


def verify(e, baseline):
    e.fixture.RequestHandlerClass = TimeModel
    old_options = os.environ.get('JAVA_TOOL_OPTIONS')
    try:
        # A legacy row is intentionally kept in its original local wall-clock form.
        old = e.submit('legacy-clock-preservation')
        e.http('POST', '/api/tasks/' + old + '/cancel', {})
        e.sql("UPDATE task SET report_time='2020-02-03 12:34:56.123456' WHERE id='" + old + "';")
        legacy = e.sql("SELECT HEX(report_time),request_digest,model_digest,tools_digest,state FROM task WHERE id='" + old + "';")
        cases = [('+08:00', 'UTC'), ('+08:00', 'America/New_York'),
                 ('+05:30', 'Asia/Shanghai'), ('+00:00', 'Asia/Shanghai')]
        if baseline:
            cases = cases[:1]
        for index, (session, appzone) in enumerate(cases):
            os.environ['JAVA_TOOL_OPTIONS'] = '-Duser.timezone=' + appzone
            e.stop(e.control)
            url = ('jdbc:mysql://127.0.0.1:' + str(e.dbport)
                   + "/jimu?connectionTimeZone=UTC&sessionVariables=time_zone='"
                   + session.replace('+', '%2B') + "'")
            cfg = {'jimu.db.url': url}
            e.control = e.start('clock-control-' + str(index), 'control', extra=cfg)
            goal = 'report-clock-' + str(index)
            before = float(e.sql('SELECT UNIX_TIMESTAMP();'))
            task = e.submit(goal)
            after = float(e.sql('SELECT UNIX_TIMESTAMP();'))
            stored = e.sql("SELECT DATE_FORMAT(report_time,'%Y-%m-%dT%H:%i:%s.%fZ') FROM task WHERE id='" + task + "';")
            epoch = datetime.fromisoformat(stored.replace('Z', '+00:00')).timestamp()
            evidence = {'sessionTimezone': session, 'applicationTimezone': appzone, 'task': task,
                        'storedReportTime': stored, 'interpretedEpoch': epoch,
                        'beforeEpoch': before, 'afterEpoch': after,
                        'offsetFromBeforeSeconds': epoch - before}
            (e.evidence / ('clock-' + str(index) + '.json')).write_text(json.dumps(evidence, indent=2))
            e.assert_that(before - 1 <= epoch <= after + 1,
                          'new report UTC epoch matches database admission clock', **evidence)
            pinned = e.sql("SELECT HEX(report_time),request_digest,model_digest,tools_digest FROM task WHERE id='" + task + "';")
            # A restart and concurrent retries must reuse the admission time, not a new now().
            e.stop(e.control)
            os.environ['JAVA_TOOL_OPTIONS'] = '-Duser.timezone=Pacific/Honolulu'
            e.control = e.start('clock-restarted-' + str(index), 'control', extra=cfg)
            body = {'goal': goal, 'capabilityIds': ['web_fetch', 'read', 'compose_report', 'present']}
            with concurrent.futures.ThreadPoolExecutor(3) as pool:
                retries = list(pool.map(lambda _: e.http('POST', '/api/tasks', body, key=goal), range(3)))
            e.assert_that(all(s == 202 and v['taskId'] == task for s, v in retries),
                          'concurrent retries after restart reuse task', case=index)
            worker = e.start('clock-worker-' + str(index), 'worker', 'T1' if index == 0 else '', extra=cfg)
            if index == 0:
                e.barrier(worker)
                e.stop(worker, kill=True)
                e.expire(task)
                worker = e.start('clock-recovery', 'worker', extra=cfg)
            view = e.state(task, 'SUCCEEDED')
            e.stop(worker)
            aid = view['artifacts'][0]['artifactId']
            status, content = e.http('GET', '/api/artifacts/' + aid + '/download')
            receipt = json.loads(e.sql("SELECT output_snapshot FROM invocation WHERE task_id='" + task + "' AND tool='compose_report';"))
            rendered = re.search(r'## 生成时间\n([^\n]+)', content).group(1)
            e.assert_that(status == 200 and datetime.fromisoformat(rendered.replace('Z', '+00:00')).timestamp() == epoch
                          and receipt['reportTime'] == rendered
                          and receipt['file']['sha256'] == hashlib.sha256(content.encode()).hexdigest(),
                          'report, composition receipt and authorized bytes retain exact admission epoch', case=index)
            clocks = [r['reportTime'] for r in e.fixture.requests if r['goal'] == goal]
            e.assert_that(len(clocks) == 5 and len(set(clocks)) == 1
                          and datetime.fromisoformat(clocks[0].replace('Z', '+00:00')).timestamp() == epoch
                          and pinned == e.sql("SELECT HEX(report_time),request_digest,model_digest,tools_digest FROM task WHERE id='" + task + "';"),
                          'worker recovery and all model rounds retain original report time and pins', case=index)
            e.assert_that(legacy == e.sql("SELECT HEX(report_time),request_digest,model_digest,tools_digest,state FROM task WHERE id='" + old + "';"),
                          'legacy DATETIME, snapshots and terminal state remain unchanged', case=index)
        e.assert_that(len(e.tls.requests) == len(cases) and all(r['path'] == '/business-source' for r in e.tls.requests),
                      'only local TLS substitutes used; recovery never refetches')
    finally:
        if old_options is None:
            os.environ.pop('JAVA_TOOL_OPTIONS', None)
        else:
            os.environ['JAVA_TOOL_OPTIONS'] = old_options


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt('report clock verification terminated')
    signal.signal(signal.SIGTERM, terminate)
    e = BusinessExperiment(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
    try:
        e.prepare()
        verify(e, '--baseline' in sys.argv[4:])
    finally:
        e.cleanup()


if __name__ == '__main__':
    main()
