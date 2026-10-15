CREATE TABLE tenant_budget (
 tenant_id VARCHAR(64) PRIMARY KEY,
 queue_limit INT NOT NULL DEFAULT 16, running_limit INT NOT NULL DEFAULT 2,
 model_limit INT NOT NULL DEFAULT 128, tool_limit INT NOT NULL DEFAULT 320,
 token_limit BIGINT NOT NULL DEFAULT 524288, storage_limit BIGINT NOT NULL DEFAULT 16777216,
 queued INT NOT NULL DEFAULT 0, running INT NOT NULL DEFAULT 0,
 model_used INT NOT NULL DEFAULT 0, tool_used INT NOT NULL DEFAULT 0,
 token_held BIGINT NOT NULL DEFAULT 0, storage_held BIGINT NOT NULL DEFAULT 0,
 CHECK (queued >= 0 AND running >= 0 AND queued <= queue_limit AND running <= running_limit),
 CHECK (model_used <= model_limit AND tool_used <= tool_limit AND token_held <= token_limit AND storage_held <= storage_limit)
) ENGINE=InnoDB;
CREATE TABLE actor_access (
 tenant_id VARCHAR(64) NOT NULL, actor_id VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, permission_version BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id, actor_id), FOREIGN KEY(tenant_id) REFERENCES tenant_budget(tenant_id)
) ENGINE=InnoDB;
CREATE TABLE installation (
 tenant_id VARCHAR(64) NOT NULL, plugin_id VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, epoch BIGINT NOT NULL DEFAULT 1,
 plugin_version VARCHAR(32) NOT NULL, plugin_digest CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id, plugin_id), FOREIGN KEY(tenant_id) REFERENCES tenant_budget(tenant_id)
) ENGINE=InnoDB;
CREATE TABLE task (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL, owner_actor_id VARCHAR(64) NOT NULL,
 idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, request_digest CHAR(64) NOT NULL,
 request_snapshot MEDIUMTEXT NOT NULL, goal TEXT NOT NULL, capabilities TEXT NOT NULL,
 installation_epoch BIGINT NOT NULL, policy_version INT NOT NULL DEFAULT 1, record_version INT NOT NULL DEFAULT 1,
 state VARCHAR(32) NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 deadline DATETIME(6) NOT NULL, ready_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 cancel_requested_at DATETIME(6), current_attempt_id CHAR(36), worker_id CHAR(36),
 fence BIGINT NOT NULL DEFAULT 0, lease_until DATETIME(6), next_event_seq BIGINT NOT NULL DEFAULT 0,
 max_models INT NOT NULL, max_tools INT NOT NULL, models_used INT NOT NULL DEFAULT 0, tools_used INT NOT NULL DEFAULT 0,
 token_held BIGINT NOT NULL DEFAULT 0, max_tokens BIGINT NOT NULL DEFAULT 65536,
 code VARCHAR(64), summary MEDIUMTEXT,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,owner_actor_id,idempotency_key),
 FOREIGN KEY(tenant_id,owner_actor_id) REFERENCES actor_access(tenant_id,actor_id),
 INDEX task_ready(tenant_id,state,ready_at,id), INDEX task_expired(tenant_id,state,lease_until,id),
 INDEX task_owner(tenant_id,owner_actor_id,created_at,id)
) ENGINE=InnoDB;
CREATE TABLE task_attempt (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL, task_id CHAR(36) NOT NULL,
 worker_id CHAR(36) NOT NULL, fence BIGINT NOT NULL, state VARCHAR(24) NOT NULL,
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), ended_at DATETIME(6),
 record_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,task_id,fence),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE step (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL, task_id CHAR(36) NOT NULL,
 attempt_id CHAR(36) NOT NULL, ordinal INT NOT NULL, state VARCHAR(24) NOT NULL,
 request_snapshot MEDIUMTEXT NOT NULL, request_digest CHAR(64) NOT NULL,
 response_snapshot MEDIUMTEXT, response_digest CHAR(64), dispatch_id CHAR(36), dispatches INT NOT NULL DEFAULT 0,
 model_version VARCHAR(32) NOT NULL DEFAULT 'fixture-v1', record_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,attempt_id,ordinal), UNIQUE(tenant_id,task_id,ordinal),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES task_attempt(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE invocation (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL, task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 attempt_id CHAR(36) NOT NULL, ordinal INT NOT NULL, slot VARCHAR(160) NOT NULL, provider_id VARCHAR(128) NOT NULL,
 effect_intent_id CHAR(36) NOT NULL, tool VARCHAR(64) NOT NULL, plugin_version VARCHAR(32) NOT NULL,
 plugin_digest CHAR(64) NOT NULL, installation_epoch BIGINT NOT NULL, input_snapshot TEXT NOT NULL, input_digest CHAR(64) NOT NULL,
 state VARCHAR(32) NOT NULL, output_snapshot MEDIUMTEXT, output_digest CHAR(64), projection_version INT NOT NULL DEFAULT 1,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), record_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,step_id,slot), UNIQUE(tenant_id,attempt_id,ordinal),
 UNIQUE(tenant_id,effect_intent_id), INDEX invocation_pending(tenant_id,state,updated_at),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id), FOREIGN KEY(tenant_id,attempt_id) REFERENCES task_attempt(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE event (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, seq BIGINT NOT NULL, event_id VARCHAR(64) NOT NULL,
 fact_id VARCHAR(128) NOT NULL, type VARCHAR(64) NOT NULL, code VARCHAR(64), status VARCHAR(32) NOT NULL, artifact_id CHAR(36),
 occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), payload_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,task_id,seq), UNIQUE(tenant_id,task_id,fact_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE reservation (
 tenant_id VARCHAR(64) NOT NULL, id VARCHAR(80) NOT NULL, task_id CHAR(36) NOT NULL,
 dispatch_id CHAR(36), category VARCHAR(24) NOT NULL, amount BIGINT NOT NULL,
 actual BIGINT, usage_quality VARCHAR(16) NOT NULL, cost_quality VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN', state VARCHAR(24) NOT NULL,
 record_version INT NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id,id), INDEX reservation_task(tenant_id,state,task_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id)
) ENGINE=InnoDB;
CREATE TABLE artifact (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL, task_id CHAR(36) NOT NULL,
 producer_invocation CHAR(36) NOT NULL, upload_id CHAR(36) NOT NULL, production_fence BIGINT NOT NULL,
 state VARCHAR(24) NOT NULL, snapshot_key VARCHAR(160), sha256 CHAR(64) NOT NULL, size BIGINT NOT NULL,
 media_type VARCHAR(64) NOT NULL DEFAULT 'text/plain; charset=utf-8',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY), record_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,task_id,producer_invocation), UNIQUE(tenant_id,upload_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,producer_invocation) REFERENCES invocation(tenant_id,id),
 INDEX artifact_cleanup(tenant_id,state,updated_at)
) ENGINE=InnoDB;
