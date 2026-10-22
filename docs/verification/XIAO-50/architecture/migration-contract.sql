-- V17 design contract only. Not executed. Backend owns the real Flyway file.
ALTER TABLE tenant_budget
 ADD COLUMN todo_storage_held BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN todo_storage_limit BIGINT NOT NULL DEFAULT 16777216,
 ADD CONSTRAINT todo_storage_capacity CHECK
  (todo_storage_held >= 0 AND todo_storage_limit >= 0 AND todo_storage_held <= todo_storage_limit);

ALTER TABLE invocation
 ADD COLUMN todo_expected_revision BIGINT,
 ADD COLUMN todo_applied_revision BIGINT,
 ADD COLUMN todo_committed_attempt_id CHAR(36),
 ADD COLUMN todo_committed_fence BIGINT,
 ADD COLUMN todo_event_seq BIGINT,
 ADD COLUMN todo_committed_at DATETIME(6),
 ADD CONSTRAINT todo_commit_attempt FOREIGN KEY(tenant_id,todo_committed_attempt_id)
  REFERENCES task_attempt(tenant_id,id),
 ADD CONSTRAINT todo_invocation_revisions CHECK
  ((todo_expected_revision IS NULL AND todo_applied_revision IS NULL)
   OR (tool='todo_write' AND todo_expected_revision IS NOT NULL AND todo_expected_revision >= 0
    AND (todo_applied_revision IS NULL OR todo_applied_revision=todo_expected_revision+1))),
 ADD CONSTRAINT todo_commit_source CHECK
  ((todo_applied_revision IS NULL AND todo_committed_attempt_id IS NULL
    AND todo_committed_fence IS NULL AND todo_event_seq IS NULL AND todo_committed_at IS NULL)
   OR (todo_applied_revision IS NOT NULL AND state='SUCCEEDED'
    AND todo_committed_attempt_id IS NOT NULL AND todo_committed_fence IS NOT NULL
    AND todo_committed_fence>0 AND todo_event_seq IS NOT NULL AND todo_event_seq>0
    AND todo_committed_at IS NOT NULL));

CREATE TABLE task_todo_projection (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, todo_version INT NOT NULL DEFAULT 1,
 binding_snapshot TEXT NOT NULL, binding_digest CHAR(64) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, content_state VARCHAR(16) NOT NULL DEFAULT 'UNWRITTEN',
 source_invocation_id CHAR(36), source_step_id CHAR(36),
 committed_attempt_id CHAR(36), committed_fence BIGINT, updated_seq BIGINT,
 pending_count INT NOT NULL DEFAULT 0, in_progress_count INT NOT NULL DEFAULT 0,
 completed_count INT NOT NULL DEFAULT 0, output_digest CHAR(64),
 updated_at DATETIME(6), retain_until DATETIME(6) NOT NULL,
 reserved_bytes BIGINT NOT NULL DEFAULT 2097152, retained_digests MEDIUMTEXT,
 PRIMARY KEY(tenant_id,task_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,source_invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,source_step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 INDEX todo_cleanup(content_state,retain_until,tenant_id,task_id),
 CHECK (record_version=1 AND todo_version=1 AND reserved_bytes=2097152),
 CHECK (revision >= 0 AND pending_count >= 0 AND completed_count >= 0
  AND in_progress_count BETWEEN 0 AND 1
  AND pending_count+in_progress_count+completed_count <= 32),
 CHECK (content_state IN ('UNWRITTEN','PRESENT','PURGED')),
 CHECK ((content_state='UNWRITTEN' AND revision=0)
  OR (content_state='PRESENT' AND revision>0) OR content_state='PURGED'),
 CHECK ((revision=0 AND source_invocation_id IS NULL AND source_step_id IS NULL
   AND committed_attempt_id IS NULL AND committed_fence IS NULL AND updated_seq IS NULL
   AND output_digest IS NULL AND updated_at IS NULL
   AND pending_count=0 AND in_progress_count=0 AND completed_count=0)
  OR (revision>0 AND source_invocation_id IS NOT NULL AND source_step_id IS NOT NULL
   AND committed_attempt_id IS NOT NULL AND committed_fence IS NOT NULL AND committed_fence>0
   AND updated_seq IS NOT NULL AND updated_seq>0 AND output_digest IS NOT NULL AND updated_at IS NOT NULL))
) ENGINE=InnoDB;

-- Cross-row checks are mandatory in the committing task-locked transaction:
-- task caps/binding/version/digest, invocation.task_id=step.task_id=task_id,
-- source invocation SUCCEEDED + tool todo_write + applied_revision=projection.revision,
-- committed attempt belongs to task, output digest and recomputed counts match.
-- No old-row DML, no down migration, no foreign-schema or service changes.
