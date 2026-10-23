"""Affected legacy slices for XIAO-55, using existing private supervised fixtures."""
import argparse
import hashlib
import json
from pathlib import Path
import signal
import traceback

from session_event_verify import SessionExperiment
from session_search_verify import SearchExperiment
from session_trace_verify import TraceExperiment
from todo_verify import TodoExperiment
from user_question_verify import QuestionsExperiment
from working_directory_verify import DirectoryExperiment

CASES = {
    'read': (SessionExperiment, 'normal'),
    'search': (SearchExperiment, 'normal'),
    'trace': (TraceExperiment, 'normal'),
    'todo': (TodoExperiment, 'normal'),
    'questions': (QuestionsExperiment, 'normal'),
    'directory': (DirectoryExperiment, 'normal'),
    'workspace-mcp': (DirectoryExperiment, 'regressions'),
}


def main():
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('foreground regression stopped')))
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--case', choices=CASES, action='append', required=True)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    results = {'jarSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(), 'cases': []}
    for name in args.case:
        kind, method = CASES[name]
        fixture = kind(args.jar, args.evidence/name)
        try:
            fixture.prepare()
            getattr(fixture, method)()
            results['cases'].append({'case': name, 'exit': 0})
        except BaseException:
            (fixture.evidence/'failure.txt').write_text(traceback.format_exc())
            results['cases'].append({'case': name, 'exit': 1})
            raise
        finally:
            fixture.cleanup()
            (args.evidence/'result.json').write_text(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
