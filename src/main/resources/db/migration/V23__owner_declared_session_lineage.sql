-- XIAO-125: owner-declared continuation, explicit closed admission scope, v5 only.
-- Fresh 0->23 and baseline 22->23 are exercised by the private MySQL fixture.
-- Existing V1-V22 remain byte-identical; no historical root or relation backfill.
ALTER TABLE task_session_history
 DROP CHECK history_supported_versions_v22,
 ADD CONSTRAINT history_supported_versions_v23
 CHECK(record_version=1 AND history_version IN (1,2,3,4,5));
ALTER TABLE session_public_event
 DROP CHECK public_event_supported_versions_v22,
 ADD CONSTRAINT public_event_supported_versions_v23
 CHECK(record_version=1 AND event_version IN (1,2,3,4,5) AND seq BETWEEN 0 AND 127);

-- Structure-version1 scope/target/step tables are reused with separate v5 schemas.
-- New policy explicit-terminal-closed-v5; authority<=4096, target<=512, context<=4096.
-- No widening, renaming or rewriting the existing authority JSONs or old policy.
CREATE TABLE task_session_lineage (
 tenant_id VARCHAR(64) NOT NULL,
 task_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1,
 genesis_snapshot TEXT,
 genesis_digest CHAR(64) NOT NULL,
 genesis_bytes INT NOT NULL,
 graph_snapshot TEXT,
 graph_digest CHAR(64) NOT NULL,
 graph_bytes INT NOT NULL,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,task_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task_session_history(tenant_id,task_id),
 CONSTRAINT lineage_record_v1 CHECK(record_version=1),
 CONSTRAINT lineage_bytes_v1 CHECK(genesis_bytes BETWEEN 1 AND 1024 AND graph_bytes BETWEEN 1 AND 16384),
 CONSTRAINT lineage_content_v1 CHECK(
  (content_state='PRESENT' AND genesis_snapshot IS NOT NULL AND graph_snapshot IS NOT NULL
   AND OCTET_LENGTH(genesis_snapshot)=genesis_bytes AND OCTET_LENGTH(graph_snapshot)=graph_bytes)
  OR (content_state='PURGED' AND genesis_snapshot IS NULL AND graph_snapshot IS NULL))
) ENGINE=InnoDB;

CREATE TABLE invocation_session_lineage_trace (
 tenant_id VARCHAR(64) NOT NULL,
 invocation_id CHAR(36) NOT NULL,
 caller_task_id CHAR(36) NOT NULL,
 step_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1,
 binding_digest CHAR(64) NOT NULL,
 input_digest CHAR(64) NOT NULL,
 authority_digest CHAR(64) NOT NULL,
 graph_digest CHAR(64) NOT NULL,
 context_digest CHAR(64) NOT NULL,
 normalized_snapshot TEXT,
 normalized_digest CHAR(64) NOT NULL,
 normalized_bytes INT NOT NULL,
 outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 failure_code VARCHAR(64),
 scan_digest CHAR(64),
 node_count INT,
 edge_count INT,
 proof_reads INT,
 proof_bytes INT,
 output_digest CHAR(64),
 output_bytes INT,
 receipt_event_seq BIGINT,
 committed_attempt_id CHAR(36),
 committed_fence BIGINT,
 committed_at DATETIME(6),
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,invocation_id),
 INDEX lineage_trace_task(tenant_id,caller_task_id,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,caller_task_id) REFERENCES task_session_lineage(tenant_id,task_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step_session_scope(tenant_id,step_id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CONSTRAINT lineage_trace_record_v1 CHECK(record_version=1 AND normalized_bytes BETWEEN 1 AND 1024),
 CONSTRAINT lineage_trace_content_v1 CHECK(
  (content_state='PRESENT' AND normalized_snapshot IS NOT NULL
   AND OCTET_LENGTH(normalized_snapshot)=normalized_bytes)
  OR (content_state='PURGED' AND normalized_snapshot IS NULL)),
 CONSTRAINT lineage_trace_outcome_v1 CHECK(
  (outcome='PENDING' AND failure_code IS NULL AND scan_digest IS NULL
   AND node_count IS NULL AND edge_count IS NULL AND proof_reads IS NULL AND proof_bytes IS NULL
   AND output_digest IS NULL AND output_bytes IS NULL AND receipt_event_seq IS NULL
   AND committed_attempt_id IS NULL AND committed_fence IS NULL AND committed_at IS NULL)
  OR (outcome IN ('SUCCEEDED','FAILED') AND receipt_event_seq IS NOT NULL
   AND receipt_event_seq BETWEEN 1 AND 256 AND committed_attempt_id IS NOT NULL
   AND committed_fence IS NOT NULL AND committed_fence>0 AND committed_at IS NOT NULL
   AND ((outcome='SUCCEEDED' AND failure_code IS NULL AND scan_digest IS NOT NULL
     AND node_count IS NOT NULL AND node_count BETWEEN 1 AND 9
     AND edge_count IS NOT NULL AND edge_count BETWEEN 0 AND 8
     AND proof_reads IS NOT NULL AND proof_reads BETWEEN 1 AND 256
     AND proof_bytes IS NOT NULL AND proof_bytes BETWEEN 1 AND 1048576
     AND output_digest IS NOT NULL AND output_bytes IS NOT NULL AND output_bytes BETWEEN 1 AND 8192)
    OR (outcome='FAILED' AND failure_code IS NOT NULL AND scan_digest IS NULL
     AND node_count IS NULL AND edge_count IS NULL AND proof_reads IS NULL AND proof_bytes IS NULL
     AND output_digest IS NULL AND output_bytes IS NOT NULL AND output_bytes=0))))
) ENGINE=InnoDB;

-- Required application invariants, checked in the SAME admission/slot transaction:
-- 1. Genesis v1 JSON exact fields: version, session_id, kind, created_at,
--    request_sha256, authority_sha256, binding_sha256; CONTINUATION additionally
--    parent_session_id,parent_genesis_sha256; ROOT must contain neither parent field.
--    All three hashes and times match original host admission records, not model text.
-- 2. Graph v1 JSON exact fields: version,caller_session_id,authority_sha256,nodes;
--    nodes sorted by UUID, each {session_id,genesis_snapshot,genesis_sha256}; count1..9.
--    Node set exactly caller+scope; no nested graphs or old invocation outputs.
-- 3. Parent is in that task's explicit scope. Source dependencies are a subset of
--    caller's explicit list, <=8 unique sources and <=64 checked target entries total.
--    Verify every source authority/current grant/pins/retention and its own graph
--    against the SAME bounded node map. Never infer parent from a scope dependency.
-- 4. Genesis/graph are insert-only until one-way PURGED NULL cleanup. Their SHA256
--    and actual UTF8 bytes are independently checked; graph nodes match live genesis.
-- 5. Slot ancestry/tenant/caller/step/provider/fence exactly match original invocation.
--    Normalized v1 JSON fields: version,target_session_id. Hash first checks original
--    bytes, semantic comparisons are field-based and do not depend on Map key order.
-- 6. scan_digest hashes canonical graph node/edge proof list used by this query.
--    Do not claim a Merkle/signature proof; this is host-ledger integrity evidence.
-- 7. Only PENDING executes. Success output stored in existing invocation table,
--    outcome + one TOOL use/reservation + reliable receipt + fence commit atomically.
--    Infrastructure/authority/corruption failures roll back and do not fabricate FAILED
--    or empty success. FAILED is reserved for the executor's existing committed-failure path.
-- 8. All new snapshots are BODY, all columns participate in actual metadata accounting.
--    Parent IDs exist only inside purgeable snapshots. Purge new graph/genesis and
--    normalized alongside all old request/context/invocation/model/event copies.
--    PENDING/PURGED remains legal without fabricated completion/receipt/scan.
-- 9. Recompute exact fixed point metadata=held=reservation.actual; release once.
--    Active writers/UNKNOWN remain held; caller/source cleanup are independent.
-- 10. Exact schema gate23. Old22 binary refuses before readiness; rollback binary
--     understands23 with v5 flags off. No downgrade or checksum repair.
