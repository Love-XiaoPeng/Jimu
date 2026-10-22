-- V18 DESIGN ONLY. Not executed; original V1-V17 stay byte-for-byte unchanged.
-- Exact final DDL must pass real V17 -> V18 MySQL upgrade and old-binary rejection.
ALTER TABLE tenant_budget
 ADD COLUMN directory_storage_held BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN directory_storage_limit BIGINT NOT NULL DEFAULT 67108864,
 ADD CONSTRAINT directory_storage_capacity CHECK
  (directory_storage_held >= 0 AND directory_storage_limit >= 0
   AND directory_storage_held <= directory_storage_limit);

ALTER TABLE step
 ADD COLUMN directory_revision BIGINT,
 ADD COLUMN directory_cwd VARCHAR(512) COLLATE utf8mb4_bin,
 ADD COLUMN directory_binding_digest CHAR(64),
 ADD CONSTRAINT directory_step_context CHECK
  ((directory_revision IS NULL AND directory_cwd IS NULL AND directory_binding_digest IS NULL)
   OR (directory_revision IS NOT NULL AND directory_revision BETWEEN 0 AND 20 AND directory_cwd IS NOT NULL
    AND directory_binding_digest IS NOT NULL)
   OR (request_cleanup_state='DIRECTORY_PURGED' AND directory_revision BETWEEN 0 AND 20
    AND directory_revision IS NOT NULL AND directory_cwd IS NULL AND directory_binding_digest IS NOT NULL));

CREATE TABLE task_directory (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, directory_version INT NOT NULL DEFAULT 1,
 path_policy_version INT NOT NULL DEFAULT 1,
 binding_snapshot TEXT NOT NULL, binding_digest CHAR(64) NOT NULL,
 namespace_digest CHAR(64) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 cwd VARCHAR(512) COLLATE utf8mb4_bin, source_invocation_id CHAR(36),
 workspace_state VARCHAR(16) NOT NULL DEFAULT 'UNINITIALIZED',
 updated_at DATETIME(6), retain_until DATETIME(6) NOT NULL,
 reserved_bytes BIGINT NOT NULL DEFAULT 8388608,
 cleanup_generation CHAR(36), retained_digests MEDIUMTEXT,
 filesystem_receipt MEDIUMTEXT,
 PRIMARY KEY(tenant_id,task_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,source_invocation_id) REFERENCES invocation(tenant_id,id),
 INDEX directory_cleanup(content_state,retain_until,tenant_id,task_id),
 CHECK (record_version=1 AND directory_version=1 AND path_policy_version=1),
 CHECK (revision BETWEEN 0 AND 20 AND reserved_bytes=8388608),
 CHECK (content_state IN ('PRESENT','PURGING','PURGED')),
 CHECK (workspace_state IN ('UNINITIALIZED','READY','PURGED')),
 CHECK ((content_state='PRESENT' AND cwd IS NOT NULL AND cleanup_generation IS NULL)
  OR (content_state IN ('PURGING','PURGED') AND cwd IS NULL AND cleanup_generation IS NOT NULL)),
 CHECK ((revision=0 AND source_invocation_id IS NULL AND updated_at IS NULL)
  OR (revision>0 AND source_invocation_id IS NOT NULL AND updated_at IS NOT NULL)),
 CHECK (content_state<>'PURGED' OR (workspace_state='PURGED' AND filesystem_receipt IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE invocation_path_binding (
 tenant_id VARCHAR(64) NOT NULL, invocation_id CHAR(36) NOT NULL,
 task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, path_policy_version INT NOT NULL DEFAULT 1,
 binding_digest CHAR(64) NOT NULL, namespace_digest CHAR(64) NOT NULL,
 slot_index INT NOT NULL, mode VARCHAR(8) NOT NULL,
 base_revision BIGINT NOT NULL, base_cwd VARCHAR(512) COLLATE utf8mb4_bin,
 target_cwd VARCHAR(512) COLLATE utf8mb4_bin,
 canonical_paths_snapshot TEXT, canonical_paths_digest CHAR(64) NOT NULL,
 input_digest CHAR(64) NOT NULL,
 applied_revision BIGINT, changed BOOLEAN,
 committed_attempt_id CHAR(36), committed_fence BIGINT,
 changed_event_seq BIGINT, committed_at DATETIME(6),
 directory_evidence_snapshot TEXT,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,invocation_id),
 UNIQUE KEY directory_step_slot(tenant_id,step_id,slot_index),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task_directory(tenant_id,task_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CHECK (record_version=1 AND path_policy_version=1),
 CHECK (slot_index BETWEEN 0 AND 3 AND base_revision BETWEEN 0 AND 20),
 CHECK (mode IN ('READ','SET','FILES') AND content_state IN ('PRESENT','PURGED')),
 CHECK (content_state='PURGED' OR (base_cwd IS NOT NULL AND canonical_paths_snapshot IS NOT NULL
  AND ((mode IN ('READ','SET') AND target_cwd IS NOT NULL)
   OR (mode='FILES' AND target_cwd IS NULL)))),
 CHECK ((applied_revision IS NULL AND changed IS NULL AND committed_attempt_id IS NULL
    AND committed_fence IS NULL AND changed_event_seq IS NULL AND committed_at IS NULL)
  OR (mode IN ('READ','SET') AND applied_revision IS NOT NULL AND changed IS NOT NULL
    AND committed_attempt_id IS NOT NULL AND committed_fence IS NOT NULL
    AND committed_fence>0 AND committed_at IS NOT NULL
    AND ((changed=TRUE AND mode='SET' AND applied_revision=base_revision+1
          AND changed_event_seq IS NOT NULL AND changed_event_seq>0)
      OR (changed=FALSE AND applied_revision=base_revision AND changed_event_seq IS NULL))))
) ENGINE=InnoDB;

-- Mandatory application checks in the original task-locked transactions:
-- * FKs alone do not prove invocation.task = step.task = projection.task or attempt.task.
-- * Verify original response array index/slot/providerId/tool/input digest and all pins.
-- * PRESENT canonical_paths_snapshot is [] for READ/SET and one canonical key for FILES.
-- * Every frozen binding is insert-only until content purge; no new cwd re-resolution.
-- * Require lower response indices SUCCEEDED and prior steps fully resolved before new slot.
-- * Directory result and applied_revision/changed/actual commit source are atomic with
--   invocation PENDING->SUCCEEDED, TOOL settlement, transition pointer and event.
-- * FILES uses the original generic file admission/UNKNOWN protocol, never this commit.
-- * task_directory.source_invocation_id points only to changed=TRUE SUCCEEDED SET,
--   applied_revision=task_directory.revision and matching output digest/cwd/event source.
-- * Snapshot consistency, exact bytes, metadata caps and final DB-time fence are mandatory.
-- * Purging clears every path/body copy, retaining original digest/length and source identity.
-- * Cleanup candidate excludes unsafe rows BEFORE LIMIT; recheck under original lock order.
-- * No old-row snapshot DML, no down migration, no shared-service changes.
