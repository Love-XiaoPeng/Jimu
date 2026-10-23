-- V22: ADR-0014 minimum scope accepted by product manager 2026-10-23.
-- Actual V21 CHECK names and exact DDL verified using private MySQL; no old backfill.
-- Confirm V21 CHECK names from SHOW CREATE TABLE. Never edit V1-V21 or repair checksums.
ALTER TABLE task_session_history
 DROP CHECK history_supported_versions_v21,
 ADD CONSTRAINT history_supported_versions_v22
 CHECK(record_version=1 AND history_version IN (1,2,3,4));
ALTER TABLE session_public_event
 DROP CHECK public_event_supported_versions_v21,
 ADD CONSTRAINT public_event_supported_versions_v22
 CHECK(record_version=1 AND event_version IN (1,2,3,4) AND seq BETWEEN 0 AND 127);

CREATE TABLE task_session_scope (
 tenant_id VARCHAR(64) NOT NULL, caller_task_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, authority_version INT NOT NULL DEFAULT 1,
 owner_actor_id VARCHAR(64) NOT NULL,
 caller_binding_digest CHAR(64) NOT NULL, caller_tools_digest CHAR(64) NOT NULL,
 authority_snapshot TEXT, authority_digest CHAR(64) NOT NULL, authority_bytes INT NOT NULL,
 candidate_count INT NOT NULL,
 grant_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME(6) NOT NULL, retain_until DATETIME(6) NOT NULL,
 revoked_at DATETIME(6), invalidated_at DATETIME(6),
 next_validation_at DATETIME(6) NOT NULL,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,caller_task_id),
 FOREIGN KEY(tenant_id,caller_task_id) REFERENCES task_session_history(tenant_id,task_id),
 INDEX scope_validation(content_state,grant_state,next_validation_at,tenant_id,caller_task_id),
 CHECK(record_version=1 AND authority_version=1 AND candidate_count BETWEEN 0 AND 8),
 CHECK(authority_bytes BETWEEN 1 AND 4096 AND retain_until>created_at),
 CHECK((grant_state='ACTIVE' AND revoked_at IS NULL)
    OR (grant_state='REVOKED' AND revoked_at IS NOT NULL)),
 CHECK((content_state='PRESENT' AND authority_snapshot IS NOT NULL
     AND OCTET_LENGTH(authority_snapshot)=authority_bytes)
    OR (content_state='PURGED' AND authority_snapshot IS NULL))
) ENGINE=InnoDB;

CREATE TABLE task_session_scope_target (
 tenant_id VARCHAR(64) NOT NULL, caller_task_id CHAR(36) NOT NULL,
 ordinal INT NOT NULL, record_version INT NOT NULL DEFAULT 1,
 source_task_id CHAR(36), target_snapshot TEXT,
 target_digest CHAR(64) NOT NULL, target_bytes INT NOT NULL,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,caller_task_id,ordinal),
 UNIQUE KEY scope_target_identity(tenant_id,caller_task_id,source_task_id),
 FOREIGN KEY(tenant_id,caller_task_id) REFERENCES task_session_scope(tenant_id,caller_task_id),
 FOREIGN KEY(tenant_id,source_task_id) REFERENCES task_session_history(tenant_id,task_id),
 CHECK(record_version=1 AND ordinal BETWEEN 0 AND 7 AND target_bytes BETWEEN 1 AND 512),
 CHECK((content_state='PRESENT' AND source_task_id IS NOT NULL AND target_snapshot IS NOT NULL
     AND OCTET_LENGTH(target_snapshot)=target_bytes)
    OR (content_state='PURGED' AND source_task_id IS NULL AND target_snapshot IS NULL))
) ENGINE=InnoDB;

CREATE TABLE step_session_scope (
 tenant_id VARCHAR(64) NOT NULL, step_id CHAR(36) NOT NULL, caller_task_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, authority_digest CHAR(64) NOT NULL,
 context_snapshot TEXT, context_digest CHAR(64) NOT NULL, context_bytes INT NOT NULL,
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,step_id),
 INDEX step_scope_task(tenant_id,caller_task_id,step_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id),
 FOREIGN KEY(tenant_id,caller_task_id) REFERENCES task_session_scope(tenant_id,caller_task_id),
 CHECK(record_version=1 AND context_bytes BETWEEN 1 AND 4096),
 CHECK((content_state='PRESENT' AND context_snapshot IS NOT NULL
     AND OCTET_LENGTH(context_snapshot)=context_bytes)
    OR (content_state='PURGED' AND context_snapshot IS NULL))
) ENGINE=InnoDB;

CREATE TABLE invocation_session_scope_query (
 tenant_id VARCHAR(64) NOT NULL, invocation_id CHAR(36) NOT NULL,
 caller_task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, operation VARCHAR(8) NOT NULL,
 binding_digest CHAR(64) NOT NULL, input_digest CHAR(64) NOT NULL,
 authority_digest CHAR(64) NOT NULL, context_digest CHAR(64) NOT NULL,
 normalized_snapshot TEXT, normalized_digest CHAR(64) NOT NULL, normalized_bytes INT NOT NULL,
 outcome VARCHAR(16) NOT NULL DEFAULT 'PENDING', failure_code VARCHAR(64),
 scan_digest CHAR(64), scan_rows INT, scan_event_bytes INT,
 read_refs_snapshot TEXT, read_refs_digest CHAR(64), read_refs_bytes INT,
 output_digest CHAR(64), output_bytes INT,
 receipt_event_seq BIGINT, native_read_seq BIGINT,
 committed_attempt_id CHAR(36), committed_fence BIGINT, committed_at DATETIME(6),
 content_state VARCHAR(16) NOT NULL DEFAULT 'PRESENT',
 PRIMARY KEY(tenant_id,invocation_id),
 INDEX scope_query_task(tenant_id,caller_task_id,invocation_id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,caller_task_id) REFERENCES task_session_scope(tenant_id,caller_task_id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step_session_scope(tenant_id,step_id),
 FOREIGN KEY(tenant_id,committed_attempt_id) REFERENCES task_attempt(tenant_id,id),
 CHECK(record_version=1 AND operation IN ('SEARCH','READ') AND normalized_bytes BETWEEN 1 AND 4096),
 CHECK((content_state='PRESENT' AND normalized_snapshot IS NOT NULL
     AND OCTET_LENGTH(normalized_snapshot)=normalized_bytes)
    OR (content_state='PURGED' AND normalized_snapshot IS NULL AND read_refs_snapshot IS NULL)),
 CHECK((read_refs_digest IS NULL AND read_refs_bytes IS NULL AND read_refs_snapshot IS NULL)
    OR (operation='READ' AND read_refs_digest IS NOT NULL AND read_refs_bytes IS NOT NULL AND read_refs_bytes BETWEEN 2 AND 2048
     AND ((content_state='PRESENT' AND read_refs_snapshot IS NOT NULL
          AND OCTET_LENGTH(read_refs_snapshot)=read_refs_bytes)
       OR (content_state='PURGED' AND read_refs_snapshot IS NULL)))),
 CONSTRAINT scope_query_terminal_shape CHECK(
  (outcome='PENDING' AND failure_code IS NULL AND scan_digest IS NULL AND scan_rows IS NULL
   AND scan_event_bytes IS NULL AND read_refs_digest IS NULL AND output_digest IS NULL
   AND output_bytes IS NULL AND receipt_event_seq IS NULL AND native_read_seq IS NULL
   AND committed_attempt_id IS NULL AND committed_fence IS NULL AND committed_at IS NULL)
  OR (outcome IN ('SUCCEEDED','FAILED')
   AND receipt_event_seq IS NOT NULL AND receipt_event_seq BETWEEN 1 AND 256
   AND committed_attempt_id IS NOT NULL AND committed_fence IS NOT NULL AND committed_fence>0
   AND committed_at IS NOT NULL
   AND ((operation='SEARCH' AND native_read_seq IS NULL)
     OR (operation='READ' AND native_read_seq IS NOT NULL AND native_read_seq BETWEEN 0 AND 127))
   AND ((outcome='SUCCEEDED' AND failure_code IS NULL AND scan_digest IS NOT NULL
     AND scan_rows IS NOT NULL AND scan_rows BETWEEN 0 AND 1024
     AND scan_event_bytes IS NOT NULL AND scan_event_bytes BETWEEN 0 AND 4194304
     AND output_digest IS NOT NULL AND output_bytes IS NOT NULL
     AND ((operation='SEARCH' AND output_bytes BETWEEN 1 AND 16384 AND read_refs_digest IS NULL)
       OR (operation='READ' AND output_bytes BETWEEN 1 AND 32768 AND read_refs_digest IS NOT NULL)))
    OR (outcome='FAILED' AND failure_code IS NOT NULL AND output_digest IS NULL
     AND output_bytes IS NOT NULL AND output_bytes=0 AND scan_digest IS NULL
     AND scan_rows IS NULL AND scan_event_bytes IS NULL
     AND ((operation='SEARCH' AND read_refs_digest IS NULL)
       OR (operation='READ' AND read_refs_digest IS NOT NULL))))))
) ENGINE=InnoDB;

-- Application invariants, enforced in the original transaction:
-- * owner/tenant/lease actor verified by host; no model-authored authority or source cuts;
-- * candidate_count equals 0..8 target rows, ordinals contiguous and sorted by source UUID;
-- * each PRESENT target snapshot equals the corresponding canonical authority entry;
-- * source is prior terminal quiescent v4, empty own scope, retained and current-pin authorized;
--   its own SESSION_HISTORY_STORAGE reservation is exempt from the unclosed-reservation test;
-- * scope and task history retain_until are the same earliest source/caller cutoff;
-- * target IDs/cuts/retention and authority/input/context digests are insert-only;
--   only one-way revoke/invalidate/scheduler metadata and one-time body purge may update;
-- * step/invocation/attempt ancestry is the caller; each candidate event proof uses source task;
-- * scan_digest = SHA256(Payloads.json(source-id ASC, seq ASC [{session_id,seq,event_sha256}]));
--   all actually scanned events, including nonmatching/filter-rejected events, are covered;
-- * read refs are actual returned rows, sorted by seq for a single target session, <=9;
--   failed read refs=[]; PENDING rows never acquire fake refs/results/receipts at purge;
-- * V21 relation tables are not reused for v4 cross-session reads; event-v4 has refs not local seqs;
-- * normalized query/input, authority/target/context/ref snapshots and source IDs are BODY;
--   purge NULLs source_task_id and all snapshots, keeps only digests/bytes/ordinal tombstones;
-- * normal response/canonical reliable result/one TOOL consumption/fence commit atomically;
-- * current whole-scope checks gate every copy, not only this tool; expiry/revocation never
--   discloses caller output/checkpoint/model-request/summary/SSE through another surface;
-- * periodic scope validation uses scope_validation keyset order, <=8 scopes/maintenance pass,
--   <=8 target PK reads each, total 2s; invalidation is monotone, active writers/UNKNOWN retained;
-- * include ALL tables and columns in real quota/fixed-point metadata + BODY cleanup receipts;
--   source and caller release only their own reservation once; no reverse cleanup waits;
-- * PENDING/PURGED is valid, preserving REVIEW-54-01; old migrations/bytes stay immutable;
-- * schema gate exactly22, V21 binaries reject before readiness; flag-off rollback understands22.
