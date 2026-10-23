-- V20 bounded session search. Promoted from ADR-0012; V1-V19 remain immutable.
-- Confirm these V19-generated names using SHOW CREATE TABLE on the private upgrade fixture.
-- A mismatch fails closed; do not drop unknown checks or edit V1-V19.
ALTER TABLE task_session_history
 DROP CHECK task_session_history_chk_1,
 ADD CONSTRAINT history_supported_versions CHECK(record_version=1 AND history_version IN (1,2));
ALTER TABLE session_public_event
 DROP CHECK session_public_event_chk_1,
 ADD CONSTRAINT public_event_supported_versions
 CHECK(record_version=1 AND event_version IN (1,2) AND seq BETWEEN 0 AND 127);
-- All other existing CHECKs, including the original five event types, stay in place.
-- Same-task/binding/event-version consistency is additionally enforced by the application.

CREATE TABLE invocation_session_search (
 tenant_id VARCHAR(64) NOT NULL, invocation_id CHAR(36) NOT NULL,
 task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1,
 binding_digest CHAR(64) NOT NULL, input_digest CHAR(64) NOT NULL,
 upper_seq BIGINT NOT NULL,
 normalized_query_snapshot TEXT, normalized_query_digest CHAR(64) NOT NULL,
 normalized_query_bytes INT NOT NULL,
 outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING', failure_code VARCHAR(64),
 scan_digest CHAR(64), scan_rows INT, scan_event_bytes INT,
 output_digest CHAR(64), output_bytes INT,
 receipt_event_seq BIGINT,
 committed_attempt_id CHAR(36), committed_fence BIGINT, committed_at DATETIME(6),
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,invocation_id),
 INDEX history_search_task(tenant_id,task_id,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task_session_history(tenant_id,task_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CONSTRAINT search_record_version CHECK(record_version=1 AND upper_seq BETWEEN 0 AND 127),
 CONSTRAINT search_query_bytes CHECK(normalized_query_bytes BETWEEN 1 AND 2048),
 CONSTRAINT search_body_state CHECK(
  (content_state='PRESENT' AND normalized_query_snapshot IS NOT NULL
   AND OCTET_LENGTH(normalized_query_snapshot)=normalized_query_bytes)
  OR (content_state='PURGED' AND normalized_query_snapshot IS NULL)),
 CONSTRAINT search_terminal_shape CHECK(
  (outcome='PENDING' AND failure_code IS NULL AND scan_digest IS NULL
   AND scan_rows IS NULL AND scan_event_bytes IS NULL AND output_digest IS NULL
   AND output_bytes IS NULL AND receipt_event_seq IS NULL
   AND committed_attempt_id IS NULL AND committed_fence IS NULL AND committed_at IS NULL)
  OR (outcome IN ('SUCCEEDED','FAILED')
   AND receipt_event_seq IS NOT NULL AND receipt_event_seq BETWEEN 1 AND 256
   AND committed_attempt_id IS NOT NULL AND committed_fence IS NOT NULL AND committed_fence>0
   AND committed_at IS NOT NULL
   AND ((outcome='SUCCEEDED' AND failure_code IS NULL AND scan_digest IS NOT NULL
     AND scan_rows IS NOT NULL AND scan_rows BETWEEN 0 AND 128
     AND scan_event_bytes IS NOT NULL AND scan_event_bytes BETWEEN 0 AND 2097152
     AND output_digest IS NOT NULL AND output_bytes IS NOT NULL AND output_bytes BETWEEN 1 AND 32768)
    OR (outcome='FAILED' AND failure_code IS NOT NULL AND scan_digest IS NULL
     AND scan_rows IS NULL AND scan_event_bytes IS NULL AND output_digest IS NULL
     AND output_bytes IS NOT NULL AND output_bytes=0))))
) ENGINE=InnoDB;

-- Application obligations:
-- * immutable query/input/binding/upper bind the original step/response slot at M2;
-- * normalized query is BODY, not metadata; clear it with every other copy at safe purge;
-- * scan_digest = SHA256(Payloads.json(ordered [{seq,event_sha256}])) for all verified
--   scanned prefix candidates, including structural/filter-rejected events; empty intersection=[];
-- * receipt_event_seq resolves to this invocation's canonical reliable tool.completed/failed
--   fact in the same transaction, never a native event seq or a raw-query receipt;
-- * no native search event; search does not claim type=session.read;
-- * source/task/step/call/pin/input ancestry and event version are revalidated per binding;
-- * all new fields/table/cleanup receipts are included in metadata fixed-point accounting;
-- * output/error + TOOL settlement + binding + reliable receipt are atomic, original replay
--   reauthorizes and returns bytes without querying later candidates;
-- * strict binary schema gate 19->20; old V19 binary rejects20 before readiness;
-- * no UPDATE/backfill/re-pin of old v1 tasks/events and no down migration.
