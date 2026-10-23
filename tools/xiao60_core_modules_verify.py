"""Changed core workflow hooks only: existing question/todo/directory normal slices."""
import argparse
from pathlib import Path
import traceback

from user_question_verify import QuestionsExperiment
from todo_verify import TodoExperiment
from working_directory_verify import DirectoryExperiment


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    for label, kind in [('questions', QuestionsExperiment), ('todos', TodoExperiment), ('directory', DirectoryExperiment)]:
        experiment = kind(args.jar, args.evidence / label)
        try:
            experiment.prepare()
            experiment.normal()
        except BaseException:
            (experiment.evidence / 'failure.txt').write_text(traceback.format_exc())
            raise
        finally:
            experiment.cleanup()


if __name__ == '__main__':
    main()
