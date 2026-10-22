"""XIAO-50 review regressions: whole-batch budgets and non-starving safe retention."""
import argparse
from pathlib import Path
import traceback
import uuid

from todo_verify import TodoExperiment, eventually, items


class ReviewExperiment(TodoExperiment):
    def batch_budget(self):
        worker = self.start('review-budget-worker', 'worker')
        for goal, plan, budget, expected in (
            ('batch-tight-first', [[items(('first', 'pending')), items(('second', 'completed'))]], 1, 0),
            ('batch-tight-later', [[items(('original', 'pending'))],
                                  [items(('rejected-one', 'pending')), items(('rejected-two', 'completed'))]], 2, 1),
        ):
            task = self.task(goal, plan, budget={'maxToolInvocations': budget})
            self.state(task, 'FAILED')
            view = self.view(task)
            facts = self.sql("SELECT code,tools_used,models_used FROM task WHERE id='"+task+"';")
            self.assert_that(facts == 'TOOL_BUDGET_EXCEEDED\t'+str(expected)+'\t'+str(expected+1),
                             'M2 rejects whole over-budget batch after prior consumption', goal=goal, ledger=facts)
            self.assert_that(view['revision'] == expected and
                             (view['todos'] is None if expected == 0 else view['todos'] == items(('original', 'pending'))['todos']),
                             'rejected response never partially replaces owner projection', goal=goal, snapshot=view)
            facts = self.sql("SELECT (SELECT COUNT(*) FROM invocation WHERE task_id='"+task+"'),"
                             "(SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='todo.updated'),"
                             "(SELECT COUNT(*) FROM event WHERE task_id='"+task+"' AND type='tool.dispatched');")
            self.assert_that(facts == '\t'.join([str(expected)]*3),
                             'no invocation or tool event created for rejected batch', goal=goal, ledger=facts)
            usage = self.sql("SELECT COUNT(*),SUM(actual),GROUP_CONCAT(DISTINCT usage_quality),GROUP_CONCAT(DISTINCT state) "
                             "FROM reservation WHERE task_id='"+task+"' AND category='MODEL';")
            self.assert_that(usage == str(expected+1)+'\t'+str(150*(expected+1))+'\tKNOWN\tSETTLED',
                             'real model usage remains known and settled despite rejection', goal=goal, ledger=usage)
            rejected = self.sql("SELECT s.state,s.response_snapshot IS NULL,"
                                "(SELECT COUNT(*) FROM invocation i WHERE i.step_id=s.id) "
                                "FROM step s JOIN model_dispatch d ON d.tenant_id=s.tenant_id AND d.id=s.dispatch_id "
                                "WHERE s.task_id='"+task+"' AND d.code='TOOL_BUDGET_EXCEEDED';")
            self.assert_that(rejected == 'REJECTED\t1\t0', 'rejected M2 stores no executable response body or slots', ledger=rejected)
        task = self.task('batch-exact-budget', [[items(('one', 'pending')), items(('two', 'completed'))]],
                         budget={'maxToolInvocations': 2})
        self.state(task, 'SUCCEEDED')
        self.assert_that(self.view(task)['revision'] == 2 and
                         self.sql("SELECT tools_used FROM task WHERE id='"+task+"';") == '2',
                         'exact batch budget admits both slots and final no-tool response')
        self.stop(worker)

    def retention_prefix(self):
        worker = self.start('review-retention-worker', 'worker')
        victim = self.task('review-other-tenant-body', [items(('other-tenant-sensitive-body', 'completed'))], subject='b')
        self.state(victim, 'SUCCEEDED')
        self.stop(worker)
        blockers = []
        # Real owner acceptance/cancellation; private fault injection is limited to unknown/writer/expiry facts.
        for kind in ('unknown', 'old-writer'):
            for index in range(32):
                task = self.task('review-prefix-'+kind+'-'+str(index), [])
                status, value = self.http('POST', '/api/tasks/'+task+'/cancel')
                assert status == 200, (status, value)
                blockers.append(task)
                if kind == 'unknown':
                    self.sql("INSERT INTO reservation(tenant_id,id,task_id,category,amount,state,usage_quality) "
                             "VALUES('tenant-a','"+task+":review-unknown','"+task+"','MODEL',1,'HELD_UNKNOWN','UNKNOWN');")
                else:
                    self.sql("INSERT INTO task_attempt(tenant_id,id,task_id,worker_id,fence,state,quiescent) "
                             "VALUES('tenant-a','"+str(uuid.uuid4())+"','"+task+"','"+str(uuid.uuid4())+"',99,'EXPIRED',FALSE);")
        # Freeze the complete blocked prefix before expiry becomes visible to maintenance.
        self.stop(self.control)
        ids = ','.join("'"+task+"'" for task in blockers)
        self.sql("UPDATE task_todo_projection SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 2 DAY WHERE task_id IN ("+ids+"); "
                 "UPDATE task_todo_projection SET retain_until=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY WHERE task_id='"+victim+"';")
        before = {
            'unknown': self.sql("SELECT id,state,usage_quality FROM reservation WHERE id LIKE '%:review-unknown' ORDER BY id;"),
            'writers': self.sql("SELECT id,worker_id,state,quiescent FROM task_attempt WHERE task_id IN ("+ids+") ORDER BY id;"),
            'quotaA': self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';"),
            'quotaB': int(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-b';")),
        }
        prefix = self.sql("SELECT task_id FROM task_todo_projection WHERE content_state<>'PURGED' "
                          "AND retain_until<=CURRENT_TIMESTAMP(6) ORDER BY retain_until,tenant_id,task_id LIMIT 32;").splitlines()
        self.assert_that(len(prefix) == 32 and victim not in prefix, 'blocked prefix exceeds bounded candidate batch', prefix=prefix)
        self.control = self.start('review-fair-cleanup-control', 'control')
        eventually(lambda: self.view(victim)['state'] == 'PURGED', 'eligible other tenant purges past 64 retained objects', 35)
        self.assert_that(self.sql("SELECT COUNT(*) FROM task_todo_projection WHERE task_id IN ("+ids+") AND content_state='UNWRITTEN';") == '64',
                         'all UNKNOWN and historical writers retain their bodies')
        self.assert_that(before['unknown'] == self.sql("SELECT id,state,usage_quality FROM reservation WHERE id LIKE '%:review-unknown' ORDER BY id;") and
                         before['writers'] == self.sql("SELECT id,worker_id,state,quiescent FROM task_attempt WHERE task_id IN ("+ids+") ORDER BY id;"),
                         'cleanup preserves all original unknown and writer facts')
        self.assert_that(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-a';") == before['quotaA'] and
                         int(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-b';")) == before['quotaB']-2097152,
                         'only safe victim releases its two MiB; retained tenant quota unchanged', before=before)
        bodies = self.sql("SELECT t.goal,p.content_state FROM task t JOIN task_todo_projection p "
                          "ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.id='"+victim+"';")
        self.assert_that('TODO_PURGED' in bodies and bodies.endswith('\tPURGED'), 'eligible victim task and projection bodies purged', ledger=bodies)
        seq = self.view(victim)['asOfSeq']
        self.stop(self.control)
        self.control = self.start('review-repeat-cleanup-control', 'control')
        self.assert_that(self.view(victim)['asOfSeq'] == seq and
                         int(self.sql("SELECT todo_storage_held FROM tenant_budget WHERE tenant_id='tenant-b';")) == before['quotaB']-2097152 and
                         self.sql("SELECT COUNT(*) FROM event WHERE task_id='"+victim+"' AND type='todo.content_deleted';") == '1',
                         'repeat maintenance does not duplicate release or deletion event')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--case', choices=('all', 'budget', 'retention'), default='all')
    args = parser.parse_args()
    experiment = ReviewExperiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        if args.case in ('all', 'budget'):
            experiment.batch_budget()
        if args.case in ('all', 'retention'):
            experiment.retention_prefix()
    except BaseException:
        (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
        raise
    finally:
        experiment.cleanup()
