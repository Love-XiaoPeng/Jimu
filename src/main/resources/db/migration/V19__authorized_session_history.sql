-- V19 authorized native session history; promoted from the frozen architecture contract.
-- Existing V1-V18 and old snapshots are immutable. No old task backfill.
ALTER TABLE tenant_budget
 ADD COLUMN history_storage_held BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN history_storage_limit BIGINT NOT NULL DEFAULT 67108864,
 ADD CONSTRAINT history_storage_capacity CHECK
 (history_storage_held>=0 AND history_storage_limit>=0
  AND history_storage_held<=history_storage_limit);

ALTER TABLE step
 ADD COLUMN session_upper_seq BIGINT,
 ADD COLUMN session_context_digest CHAR(64),
 ADD COLUMN session_binding_digest CHAR(64),
 ADD CONSTRAINT step_session_boundary CHECK
 ((session_upper_seq IS NULL AND session_context_digest IS NULL AND session_binding_digest IS NULL)
 OR (session_upper_seq BETWEEN 0 AND 127 AND session_upper_seq IS NOT NULL
  AND session_context_digest IS NOT NULL AND session_binding_digest IS NOT NULL));

CREATE TABLE task_session_history (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, history_version INT NOT NULL DEFAULT 1,
 binding_snapshot TEXT NOT NULL, binding_digest CHAR(64) NOT NULL,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 next_seq BIGINT NOT NULL DEFAULT 0,
 body_bytes BIGINT NOT NULL DEFAULT 0, metadata_bytes BIGINT NOT NULL DEFAULT 0,
 reserved_bytes BIGINT NOT NULL DEFAULT 8388608,
 held_bytes BIGINT NOT NULL DEFAULT 8388608,
 retain_until DATETIME(6) NOT NULL,
 cleanup_receipt TEXT,
 PRIMARY KEY(tenant_id,task_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 INDEX history_cleanup(content_state,retain_until,tenant_id,task_id),
 CHECK(record_version=1 AND history_version=1),
 CHECK(content_state IN ('PRESENT','PURGED') AND next_seq BETWEEN 0 AND 128),
 CHECK(body_bytes BETWEEN 0 AND 7340032 AND metadata_bytes BETWEEN 0 AND 65536),
 CHECK(reserved_bytes=8388608),
 CHECK((content_state='PRESENT' AND cleanup_receipt IS NULL AND held_bytes=8388608)
  OR (content_state='PURGED' AND body_bytes=0 AND cleanup_receipt IS NOT NULL AND held_bytes=metadata_bytes))
) ENGINE=InnoDB;

CREATE TABLE session_public_event (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, seq BIGINT NOT NULL,
 record_version INT NOT NULL DEFAULT 1, event_version INT NOT NULL DEFAULT 1,
 fact_key VARCHAR(96) NOT NULL, type VARCHAR(32) NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 event_snapshot MEDIUMTEXT, event_digest CHAR(64) NOT NULL, event_bytes INT NOT NULL,
 source_digest CHAR(64) NOT NULL,
 source_step_id CHAR(36), source_invocation_id CHAR(36),
 committed_attempt_id CHAR(36), committed_fence BIGINT,
 reliable_event_seq BIGINT NOT NULL,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,task_id,seq), UNIQUE KEY history_fact(tenant_id,task_id,fact_key),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task_session_history(tenant_id,task_id),
 FOREIGN KEY(tenant_id,source_step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,source_invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CHECK(record_version=1 AND event_version=1 AND seq BETWEEN 0 AND 127),
 CHECK(type IN ('user.message','step.started','model.response','session.read','turn.ended')),
 CHECK(event_bytes BETWEEN 1 AND 16384 AND reliable_event_seq BETWEEN 1 AND 256),
 CHECK((content_state='PRESENT' AND event_snapshot IS NOT NULL AND OCTET_LENGTH(event_snapshot)=event_bytes)
  OR (content_state='PURGED' AND event_snapshot IS NULL)),
 CHECK((committed_attempt_id IS NULL AND committed_fence IS NULL)
  OR (committed_attempt_id IS NOT NULL AND committed_fence IS NOT NULL AND committed_fence>0))
) ENGINE=InnoDB;

CREATE TABLE invocation_session_read (
 tenant_id VARCHAR(64) NOT NULL, invocation_id CHAR(36) NOT NULL,
 task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, binding_digest CHAR(64) NOT NULL,
 input_digest CHAR(64) NOT NULL, upper_seq BIGINT NOT NULL,
 target_seq BIGINT NOT NULL, before_count INT NOT NULL, after_count INT NOT NULL,
 outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING', failure_code VARCHAR(64),
 window_start BIGINT, window_end BIGINT, target_digest CHAR(64),
 output_digest CHAR(64), output_bytes INT,
 receipt_seq BIGINT, committed_attempt_id CHAR(36), committed_fence BIGINT,
 committed_at DATETIME(6), content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,invocation_id),
 INDEX history_read_task(tenant_id,task_id,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task_session_history(tenant_id,task_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CHECK(record_version=1 AND upper_seq BETWEEN 0 AND 127),
 CHECK(target_seq BETWEEN 0 AND 9007199254740991),
 CHECK(before_count BETWEEN 0 AND 4 AND after_count BETWEEN 0 AND 4),
 CHECK(content_state IN ('PRESENT','PURGED')),
 CHECK((outcome='PENDING' AND failure_code IS NULL AND window_start IS NULL AND window_end IS NULL
   AND target_digest IS NULL AND output_digest IS NULL AND output_bytes IS NULL
   AND receipt_seq IS NULL AND committed_attempt_id IS NULL AND committed_fence IS NULL AND committed_at IS NULL)
  OR (outcome IN ('SUCCEEDED','FAILED') AND receipt_seq BETWEEN 0 AND 127 AND receipt_seq IS NOT NULL
   AND committed_attempt_id IS NOT NULL AND committed_fence IS NOT NULL AND committed_fence>0 AND committed_at IS NOT NULL
   AND ((outcome='SUCCEEDED' AND failure_code IS NULL AND target_digest IS NOT NULL
     AND output_digest IS NOT NULL AND output_bytes BETWEEN 1 AND 32768 AND output_bytes IS NOT NULL
     AND window_start IS NOT NULL AND window_end IS NOT NULL
     AND window_start<=target_seq AND target_seq<=window_end AND window_end<=upper_seq)
    OR (outcome='FAILED' AND failure_code IS NOT NULL AND target_digest IS NULL
     AND output_digest IS NULL AND output_bytes=0 AND output_bytes IS NOT NULL
     AND window_start IS NULL AND window_end IS NULL))))
) ENGINE=InnoDB;

-- Application invariants (FKs alone do not prove same-task ancestry):
-- * all event/step/invocation/attempt source task IDs equal this binding's task;
-- * public-v1 JSON Schema, exact snapshot digest/bytes, source digest and source kind;
-- * user.message=seq0; fact_key is immutable; no event UPDATE except one content purge;
-- * every fact append, next_seq, body/metadata counters and history.changed are atomic;
-- * source_step includes the original response array, slot, raw input and pins;
-- * upper_seq/input/binding are insert-only at M2; succeeded result never re-queries;
-- * result/error, receipt, TOOL settlement and invocation terminal state are atomic;
-- * failed transaction/commit-unknown never becomes a fabricated bound result;
-- * step context appears in original checkpoint; original replay never refreshes it;
-- * native seq and reliable_event_seq are separate namespaces; no nearest-event search;
-- * count all old-table body copies for this profile in body_bytes/metadata_bytes;
-- * exact cleanup and unique release preserve original source/digest/bytes;
-- * retained tombstones stay charged: SETTLED storage.actual=held_bytes=metadata_bytes;
--   tenant held releases only reserved_bytes-held_bytes once, never the retained metadata;
-- * old rows have NULL step columns; strict binary schema gate changes 18 -> 19;
-- * one task-locked PK range SELECT returns at most 9 rows; no OFFSET, FTS or file fallback.
