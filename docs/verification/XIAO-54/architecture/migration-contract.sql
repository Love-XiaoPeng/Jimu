-- V21 candidate: captured read provenance and bounded trace. Product decision pending.
-- Validate V20 named CHECKs using SHOW CREATE TABLE; mismatch must fail closed.
-- Never edit V1-V20, backfill old facts, or execute against shared/production data.
ALTER TABLE task_session_history
 DROP CHECK history_supported_versions,
 ADD CONSTRAINT history_supported_versions_v21
 CHECK(record_version=1 AND history_version IN (1,2,3));
ALTER TABLE session_public_event
 DROP CHECK public_event_supported_versions,
 ADD CONSTRAINT public_event_supported_versions_v21
 CHECK(record_version=1 AND event_version IN (1,2,3) AND seq BETWEEN 0 AND 127);

ALTER TABLE invocation_session_read
 ADD COLUMN relation_version INT NOT NULL DEFAULT 0,
 ADD COLUMN read_sources_snapshot TEXT,
 ADD COLUMN read_sources_digest CHAR(64),
 ADD COLUMN read_sources_bytes INT,
 ADD CONSTRAINT read_relation_shape CHECK(
  (relation_version=0 AND read_sources_snapshot IS NULL
   AND read_sources_digest IS NULL AND read_sources_bytes IS NULL)
  OR (relation_version=1 AND
   ((outcome='PENDING' AND content_state='PRESENT' AND read_sources_snapshot IS NULL
     AND read_sources_digest IS NULL AND read_sources_bytes IS NULL)
    OR (outcome IN ('SUCCEEDED','FAILED') AND read_sources_digest IS NOT NULL
      AND read_sources_bytes IS NOT NULL AND read_sources_bytes BETWEEN 2 AND 1024
      AND ((content_state='PRESENT' AND read_sources_snapshot IS NOT NULL
        AND OCTET_LENGTH(read_sources_snapshot)=read_sources_bytes)
       OR (content_state='PURGED' AND read_sources_snapshot IS NULL))))));

CREATE TABLE invocation_session_trace (
 tenant_id VARCHAR(64) NOT NULL, invocation_id CHAR(36) NOT NULL,
 task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1,
 binding_digest CHAR(64) NOT NULL, input_digest CHAR(64) NOT NULL,
 upper_seq BIGINT NOT NULL, target_seq BIGINT NOT NULL,
 relation_policy VARCHAR(32) NOT NULL,
 outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING', failure_code VARCHAR(64),
 scan_digest CHAR(64), relations_digest CHAR(64), target_digest CHAR(64),
 scan_rows INT, scan_event_bytes INT, relation_edges INT,
 output_digest CHAR(64), output_bytes INT, receipt_event_seq BIGINT,
 committed_attempt_id CHAR(36), committed_fence BIGINT, committed_at DATETIME(6),
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,invocation_id),
 INDEX history_trace_task(tenant_id,task_id,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task_session_history(tenant_id,task_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CONSTRAINT trace_version_scope CHECK(record_version=1 AND upper_seq BETWEEN 0 AND 127
  AND target_seq BETWEEN 0 AND 9007199254740991
  AND relation_policy='read-receipt-sources-v1'),
 CONSTRAINT trace_content_state CHECK(content_state IN ('PRESENT','PURGED')),
 CONSTRAINT trace_terminal_shape CHECK(
  (outcome='PENDING' AND failure_code IS NULL AND scan_digest IS NULL
   AND relations_digest IS NULL AND target_digest IS NULL AND scan_rows IS NULL
   AND scan_event_bytes IS NULL AND relation_edges IS NULL AND output_digest IS NULL
   AND output_bytes IS NULL AND receipt_event_seq IS NULL
   AND committed_attempt_id IS NULL AND committed_fence IS NULL AND committed_at IS NULL)
  OR (outcome IN ('SUCCEEDED','FAILED') AND receipt_event_seq IS NOT NULL
   AND receipt_event_seq BETWEEN 1 AND 256 AND committed_attempt_id IS NOT NULL
   AND committed_fence IS NOT NULL AND committed_fence>0 AND committed_at IS NOT NULL
   AND ((outcome='SUCCEEDED' AND failure_code IS NULL AND scan_digest IS NOT NULL
     AND relations_digest IS NOT NULL AND target_digest IS NOT NULL
     AND scan_rows IS NOT NULL AND scan_rows BETWEEN 1 AND 128
     AND scan_event_bytes IS NOT NULL AND scan_event_bytes BETWEEN 1 AND 2097152
     AND relation_edges IS NOT NULL AND relation_edges BETWEEN 0 AND 180
     AND output_digest IS NOT NULL AND output_bytes IS NOT NULL
     AND output_bytes BETWEEN 1 AND 32768)
    OR (outcome='FAILED' AND failure_code IS NOT NULL AND scan_digest IS NULL
     AND relations_digest IS NULL AND target_digest IS NULL AND scan_rows IS NULL
     AND scan_event_bytes IS NULL AND relation_edges IS NULL AND output_digest IS NULL
     AND output_bytes IS NOT NULL AND output_bytes=0))))
) ENGINE=InnoDB;

-- Application invariants, not provable by cross-table CHECK/FK:
-- * v3 M2 read bindings explicitly relation_version=1; old bindings remain0/NULL.
-- * source snapshot is BODY, <=1024 bytes, canonical [{seq,event_sha256}] in seq order.
-- * successful read snapshots exactly actual returned target/neighbor rows, <=9;
--   failed reads snapshot=[], no source edges. Same tenant/task, seq<receipt seq,
--   seq<=original upper, actual digests; no user/model supplied relationship facts.
-- * event-v3 source_event_seqs equals this snapshot's seq projection only on reads.
-- * no surface/replacement or arbitrary causal edges, no old-event backfill.
-- * trace per original slot: immutable input/binding/upper/target/policy; same-task
--   step/response/attempt/fence/pins and current authority checked in transaction.
-- * scan_digest covers all prefix event seq/digest pairs; relations_digest covers
--   validated read receipt seq/read_sources_digest pairs in seq order.
-- * output stored only in invocation, no native trace event or expanded old output.
-- * input/upper/facts/result/one TOOL consumption/canonical receipt commit atomically.
-- * at safe purge, clear read_sources_snapshot plus ALL old body copies; preserve
--   digests/bytes/receipt tombstones, purge trace content_state, fixed-point metadata
--   charge including new columns/table and settlement's own changing field widths.
-- * schema gate exactly21; old V20 control/worker reject21 before readiness.
