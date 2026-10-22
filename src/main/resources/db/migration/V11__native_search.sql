-- Forward-only. No production installations, policies, historical tasks or reservations change.
CREATE TABLE native_search_run (
 tenant_id VARCHAR(64) NOT NULL, root_invocation CHAR(36) NOT NULL, task_id CHAR(36) NOT NULL,
 effect_id CHAR(36) NOT NULL, attempt_id CHAR(36) NOT NULL, worker_id CHAR(36) NOT NULL,
 host_id CHAR(36) NOT NULL, runtime_directory VARCHAR(1024) NOT NULL,
 snapshot_digest CHAR(64) NOT NULL, query_digest CHAR(64) NOT NULL, tool_pin VARCHAR(160) NOT NULL,
 phase VARCHAR(16) NOT NULL DEFAULT 'PREPARED', process_identity TEXT,
 storage_removed BOOLEAN NOT NULL DEFAULT FALSE,
 result_hash CHAR(64), result_size BIGINT, cursor_index MEDIUMTEXT,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), stopped_at DATETIME(6),
 expires_at DATETIME(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY),
 record_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,root_invocation), UNIQUE(tenant_id,effect_id),
 FOREIGN KEY(tenant_id,root_invocation) REFERENCES invocation(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES task_attempt(tenant_id,id),
 INDEX native_search_active(host_id,phase,root_invocation),
 INDEX native_search_task(tenant_id,task_id), INDEX native_search_expiry(tenant_id,expires_at)
) ENGINE=InnoDB;
CREATE TABLE native_search_stop (
 tenant_id VARCHAR(64) NOT NULL, root_invocation CHAR(36) NOT NULL,
 identity_digest CHAR(64) NOT NULL, process_identity TEXT NOT NULL,
 observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), record_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,root_invocation,identity_digest),
 FOREIGN KEY(tenant_id,root_invocation) REFERENCES native_search_run(tenant_id,root_invocation)
) ENGINE=InnoDB;
