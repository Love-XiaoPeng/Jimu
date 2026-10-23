"""REVIEW-55-01: real v4 READ receipts, flat proofs and exact-slot recovery."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import traceback

from p2_verify import Handler
from prior_session_verify import PriorExperiment


def canonical(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


class ReadModel(Handler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        goal = next(m['content'] for m in body['messages'] if m['role'] == 'user')
        context = json.loads(body['messages'][-1]['content'])
        replies = [json.loads(m['content']) for m in body['messages'] if m['role'] == 'tool']
        assert context['version'] == 4
        message = {'role': 'assistant', 'content': 'Public nebula ' + '🧱' * 270}
        call = None
        if goal.startswith('receipt-recovery') and not replies:
            call = ('session_event_read', {'session_id': context['prior_sessions'][0]['session_id'], 'seq': 2, 'before': 1, 'after': 1})
        elif goal.startswith('receipt ') and not replies:
            call = ('session_search', {'query': 'nebula'})
        elif goal.startswith('receipt ') and len(replies) == 1:
            seq, before, after = self.server.windows[goal]
            call = ('session_event_read', {'session_id': replies[0]['items'][0]['session_id'], 'seq': seq, 'before': before, 'after': after})
        elif goal == 'receipt nested' and len(replies) == 2:
            call = ('session_event_read', {'seq': context['upper_seq'], 'before': 1})
        if call:
            name, arguments = call
            message['tool_calls'] = [{'id': 'receipt_' + str(len(replies)), 'type': 'function', 'function': {'name': name, 'arguments': canonical(arguments)}}]
        with self.server.lock:
            self.server.models[goal] = self.server.models.get(goal, 0) + 1
            self.server.requests.append({'goal': goal, 'body': body, 'context': context, 'replies': replies})
        self.send(200, {'id': 'read-receipt-fixture', 'choices': [{'index': 0, 'message': message, 'finish_reason': 'tool_calls' if call else 'stop'}], 'usage': {'prompt_tokens': 100, 'completion_tokens': 50, 'total_tokens': 150}})


class ReadExperiment(PriorExperiment):
    def output_and_receipt(self, task):
        invocation, step, output, receipt = self.sql("SELECT i.id,i.step_id,i.output_snapshot,q.native_read_seq FROM invocation i JOIN invocation_session_scope_query q ON q.tenant_id=i.tenant_id AND q.invocation_id=i.id WHERE i.task_id='" + task + "' AND i.tool='session_event_read' ORDER BY q.native_read_seq LIMIT 1;").split('\t')
        return invocation, step, json.loads(output), int(receipt)

    def check_receipts(self, task):
        rows = self.sql("SELECT event_snapshot FROM session_public_event WHERE task_id='" + task + "' AND type='session.read' ORDER BY seq;")
        self.assert_that(bool(rows), 'caller has an actual native READ receipt', task=task)
        for raw in rows.splitlines():
            event = json.loads(raw)
            actual = self.event(task, event['seq'])['target']
            self.assert_that(actual == event, 'public receipt/history read is 200 and exact', task=task, seq=event['seq'], type=event['type'])
            if event['type'] == 'session.read':
                self.assert_that('target' not in event['data'] and 'output' not in event['data'], 'receipt does not recursively embed output')

    def tamper_output(self, task, field):
        invocation, step, output, receipt_seq = self.output_and_receipt(task)
        original_output = canonical(output)
        original_receipt = self.sql("SELECT event_snapshot FROM session_public_event WHERE task_id='" + task + "' AND seq=" + str(receipt_seq) + ";")

        def store(value, receipt):
            raw = canonical(value)
            digest = hashlib.sha256(raw.encode()).hexdigest()
            receipt['data']['output_sha256'] = digest
            receipt['data']['output_bytes'] = len(raw.encode())
            event_raw = canonical(receipt)
            event_digest = hashlib.sha256(event_raw.encode()).hexdigest()
            self.sql("UPDATE invocation SET output_snapshot=CONVERT(0x" + raw.encode().hex() + " USING utf8mb4),output_digest='" + digest + "' WHERE id='" + invocation + "'; UPDATE invocation_session_scope_query SET output_digest='" + digest + "',output_bytes=" + str(len(raw.encode())) + " WHERE invocation_id='" + invocation + "'; UPDATE session_public_event SET event_snapshot=CONVERT(0x" + event_raw.encode().hex() + " USING utf8mb4),event_digest='" + event_digest + "',event_bytes=" + str(len(event_raw.encode())) + " WHERE task_id='" + task + "' AND seq=" + str(receipt_seq) + ";")

        changed = json.loads(original_output)
        if field == 'target':
            changed['target']['data']['content'] = 'changed full event'
        else:
            changed[field][0]['text'] = 'changed clipped summary'
        try:
            store(changed, json.loads(original_receipt))
            result = self.event(task, receipt_seq, status=409)
            self.assert_that(result['code'] == 'SESSION_HISTORY_CORRUPT', 'coordinated outer hash changes cannot forge ' + field, invocation=invocation, step=step)
        finally:
            store(json.loads(original_output), json.loads(original_receipt))
        self.event(task, receipt_seq)

    def run(self):
        self.prepare()
        self.fixture.RequestHandlerClass = ReadModel
        self.fixture.windows = {
            'receipt zero': (2, 0, 0),
            'receipt before': (3, 2, 0),
            'receipt after': (0, 0, 2),
            'receipt both': (2, 1, 1),
            'receipt clipped': (0, 1, 2),
            'receipt nested': (2, 1, 1),
        }
        sources = [self.prior_task('source ' + str(index) + ' nebula ' + '🧱' * 270) for index in range(2)]
        workers = [self.start('read-source-worker-' + str(index), 'worker') for index in range(2)]
        for task in sources:
            self.state(task, 'SUCCEEDED')
            self.quiet(task)
        for worker in workers:
            self.stop(worker)
        cases = ['receipt clipped'] if self.expect_bug else list(self.fixture.windows)
        callers = {goal: self.prior_task(goal, sources) for goal in cases}
        workers = [self.start('read-caller-worker-' + str(index), 'worker') for index in range(2)]
        for goal, task in callers.items():
            self.state(task, 'SUCCEEDED')
            self.quiet(task)
            invocation, step, output, receipt = self.output_and_receipt(task)
            self.assert_that(len(output['before']) == min(self.fixture.windows[goal][0], self.fixture.windows[goal][1]) and len(output['after']) == min(3-self.fixture.windows[goal][0], self.fixture.windows[goal][2]), 'real clipped window ' + goal, invocation=invocation, step=step)
            if self.expect_bug:
                self.assert_that(self.event(task, receipt, status=409)['code'] == 'SESSION_HISTORY_CORRUPT', 'REPRODUCED REVIEW-55-01 on original JAR; not product success')
            else:
                self.check_receipts(task)
                output_refs = json.loads(self.sql("SELECT read_refs_snapshot FROM invocation_session_scope_query WHERE invocation_id='" + invocation + "';"))
                for ref in output_refs:
                    digest = self.sql("SELECT event_digest FROM session_public_event WHERE task_id='" + ref['session_id'] + "' AND seq=" + str(ref['seq']) + ";")
                    self.assert_that(ref['event_sha256'] == digest, 'reference retains full event digest', seq=ref['seq'])
                if goal == 'receipt after':
                    self.assert_that(output['after'][-1]['summary_truncated'] and len(output['after'][-1]['text']) == 256, 'Unicode neighbor summary truncation preserved')
        for worker in workers:
            self.stop(worker)
        if self.expect_bug:
            return
        self.tamper_output(callers['receipt after'], 'after')
        self.tamper_output(callers['receipt before'], 'before')
        self.tamper_output(callers['receipt both'], 'target')
        _, _, read, receipt = self.output_and_receipt(callers['receipt after'])
        source = read['session_id']
        original_digest = self.sql("SELECT event_digest FROM session_public_event WHERE task_id='" + source + "' AND seq=1;")
        try:
            self.sql("UPDATE session_public_event SET event_digest=REPEAT('f',64) WHERE task_id='" + source + "' AND seq=1;")
            self.assert_that(self.event(callers['receipt after'], receipt, status=409)['code'] == 'SESSION_HISTORY_CORRUPT', 'neighbor full event digest corruption is rejected')
        finally:
            self.sql("UPDATE session_public_event SET event_digest='" + original_digest + "' WHERE task_id='" + source + "' AND seq=1;")
        recovery = self.prior_task('receipt-recovery exact READ', sources)
        worker = self.start('read-original-pending', 'worker', boundary='SESSION_COMMIT_BEFORE')
        self.barrier(worker)
        invocation, step = self.sql("SELECT id,step_id FROM invocation WHERE task_id='" + recovery + "' AND tool='session_event_read';").split('\t')
        self.scenario(worker, recovery, 'read-fault', invocation, step)
        original = self.sql("SELECT output_snapshot,output_digest FROM invocation WHERE id='" + invocation + "' AND step_id='" + step + "';")
        cut = self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE id='" + step + "';")
        original_fence = self.sql("SELECT fence FROM task WHERE id='" + recovery + "';")
        self.check_receipts(recovery)
        self.stop(worker, kill=True)
        self.expire(recovery)
        resumed = self.start('read-successful-takeover', 'worker')
        self.state(recovery, 'SUCCEEDED')
        self.quiet(recovery)
        self.assert_that(original == self.sql("SELECT output_snapshot,output_digest FROM invocation WHERE id='" + invocation + "' AND step_id='" + step + "';") and cut == self.sql("SELECT session_upper_seq,session_context_digest FROM step WHERE id='" + step + "';"), 'READ takeover preserves exact original bytes and cut', invocation=invocation, step=step)
        self.assert_that(int(self.sql("SELECT fence FROM task WHERE id='" + recovery + "';")) > int(original_fence), 'READ takeover uses a new fenced attempt')
        self.assert_that(self.sql("SELECT tools_used FROM task WHERE id='" + recovery + "';") == '1' and self.sql("SELECT COUNT(*) FROM session_public_event WHERE task_id='" + recovery + "' AND type='session.read';") == '1' and self.sql("SELECT COUNT(*),SUM(actual) FROM reservation WHERE id='" + invocation + "' AND category='TOOL';") == '1\t1', 'READ rollback/lost-ack/replay/takeover consumes one tool, reservation and receipt')
        requests = [request for request in self.fixture.requests if request['goal'] == 'receipt-recovery exact READ']
        self.assert_that(requests[-1]['replies'] == [json.loads(original.split('\t')[0])], 'resumed model receives exact original READ output')
        self.check_receipts(recovery)
        self.scenario(resumed, recovery, 'account')
        self.stop(resumed)
        (self.evidence / 'model-requests.json').write_text(json.dumps(self.fixture.requests, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground supervisor terminated')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--expect-bug', action='store_true')
    args = parser.parse_args()
    experiment = ReadExperiment(args.jar, args.evidence)
    experiment.expect_bug = args.expect_bug
    try:
        experiment.run()
    except BaseException:
        (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
