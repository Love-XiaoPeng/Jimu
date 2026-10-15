-- Lease/state changes fence commits; they do not prove that external writes have stopped.
-- Existing attempts remain unknown until their worker exits or reports quiescence.
ALTER TABLE task_attempt ADD COLUMN quiescent BOOLEAN NOT NULL DEFAULT FALSE;
