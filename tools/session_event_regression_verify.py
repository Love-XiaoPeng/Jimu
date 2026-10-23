"""Run only the legacy profiles touched by session history integration, on one supplied JAR."""
import argparse
import json
from pathlib import Path
import traceback

from working_directory_verify import DirectoryExperiment
from todo_verify import TodoExperiment
from user_question_verify import QuestionsExperiment

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    outcomes = []
    for name, kind, method in [('directory', DirectoryExperiment, 'normal'),
        ('workspace-mcp', DirectoryExperiment, 'regressions'),
        ('todo', TodoExperiment, 'normal'), ('question', QuestionsExperiment, 'normal')]:
        experiment = kind(args.jar, args.evidence/name)
        try:
            experiment.prepare()
            getattr(experiment, method)()
            outcomes.append({'case':name, 'passed':True})
        except BaseException:
            (experiment.evidence/'failure.txt').write_text(traceback.format_exc())
            outcomes.append({'case':name, 'passed':False})
        finally:
            experiment.cleanup()
    (args.evidence/'outcomes.json').write_text(json.dumps(outcomes, indent=2))
    assert all(row['passed'] for row in outcomes), outcomes
