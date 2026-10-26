"""REVIEW-75-01: normalize only the five approved schema22 projection changes."""
import json
from pathlib import Path

_columns = json.loads((Path(__file__).resolve().parents[1] /
    'docs/verification/XIAO-60/evidence/b2-invocation-removed-sql.json').read_text())['columns']
_queries = {
    'SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND id=? FOR UPDATE': 'user_question',
    'SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND owner_actor_id=? AND answer_key=?': 'user_question',
    'SELECT * FROM user_question WHERE tenant_id=? AND id=?': 'user_question',
    'SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND slot=? FOR UPDATE': 'invocation',
    "SELECT * FROM user_question WHERE tenant_id=? AND task_id=? AND phase='OPEN' AND foreground_due_at<=CURRENT_TIMESTAMP(6) FOR UPDATE": 'user_question',
}


def expanded_sql(sql):
    table = _queries.get(sql)
    return sql if table is None else sql.replace('SELECT *', 'SELECT ' + ','.join(_columns[table]), 1)


def expected_sql(role, original):
    return expanded_sql(original) if role == 'new' else original
