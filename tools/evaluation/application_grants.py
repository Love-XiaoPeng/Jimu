"""Generate reviewable SQL only. Never connects; scope/history/approval are SELECT-only."""
import argparse
import re

APPLICATION_TABLES = (
    'actor_access', 'artifact', 'evaluation_budget', 'event', 'installation', 'invocation',
    'model_dispatch', 'reservation', 'step', 'task', 'task_attempt', 'tenant_budget', 'worker_process',
    'native_search_run',
    'task_image_input', 'task_image_object',
)


def grants(database, user, host, partial_revokes=False):
    if not re.fullmatch(r'[a-z][a-z0-9_]*', database) or not re.fullmatch(r'[a-z][a-z0-9_]*', user) or not re.fullmatch(r'[A-Za-z0-9.:-]+', host):
        raise ValueError('Explicit database, account and bound source required')
    account = "'" + user + "'@'" + host + "'"
    pattern = database if partial_revokes else database.replace('_', r'\_')
    return ['GRANT SELECT ON `' + pattern + '`.* TO ' + account + ';'] + [
        'GRANT INSERT,UPDATE,DELETE ON `' + database + '`.`' + table + '` TO ' + account + ';'
        for table in APPLICATION_TABLES] + [
        'GRANT INSERT ON `' + database + '`.`native_search_stop` TO ' + account + ';',
        'GRANT INSERT,UPDATE ON `' + database + '`.`user_question` TO ' + account + ';',
        'GRANT INSERT,UPDATE ON `' + database + '`.`task_todo_projection` TO ' + account + ';',
        'GRANT INSERT,UPDATE ON `' + database + '`.`mcp_io` TO ' + account + ';',
        'GRANT INSERT,UPDATE ON `' + database + '`.`mcp_result` TO ' + account + ';',
        'GRANT INSERT,DELETE ON `' + database + '`.`mcp_cursor` TO ' + account + ';']


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('database'); parser.add_argument('user'); parser.add_argument('host')
    parser.add_argument('--partial-revokes', action='store_true')
    args = parser.parse_args()
    print('\n'.join(grants(args.database, args.user, args.host, args.partial_revokes)))
