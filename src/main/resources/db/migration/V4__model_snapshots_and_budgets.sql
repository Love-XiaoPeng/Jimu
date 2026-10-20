ALTER TABLE task
 ADD COLUMN model_snapshot MEDIUMTEXT,
 ADD COLUMN model_digest CHAR(64),
 ADD COLUMN evaluation_run_id VARCHAR(64),
 ADD COLUMN cost_held BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN max_cost_micros BIGINT NOT NULL DEFAULT 1000000;
ALTER TABLE tenant_budget
 ADD COLUMN cost_held BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN cost_limit BIGINT NOT NULL DEFAULT 1000000;
ALTER TABLE step MODIFY COLUMN model_version VARCHAR(160) NOT NULL DEFAULT 'fixture-v1';

CREATE TABLE evaluation_budget (
 tenant_id VARCHAR(64) NOT NULL, id VARCHAR(64) NOT NULL,
 max_requests INT NOT NULL DEFAULT 20, requests_used INT NOT NULL DEFAULT 0,
 max_cost_micros BIGINT NOT NULL DEFAULT 1000000, cost_held BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(16) NOT NULL DEFAULT 'OPEN', policy_version INT NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id) REFERENCES tenant_budget(tenant_id),
 CHECK(max_requests BETWEEN 1 AND 20 AND requests_used BETWEEN 0 AND max_requests),
 CHECK(max_cost_micros BETWEEN 1 AND 1000000 AND cost_held >= 0)
) ENGINE=InnoDB;
ALTER TABLE task ADD FOREIGN KEY(tenant_id,evaluation_run_id) REFERENCES evaluation_budget(tenant_id,id);

CREATE TABLE model_dispatch (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL,
 task_id CHAR(36) NOT NULL, step_id CHAR(36) NOT NULL,
 config_digest CHAR(64) NOT NULL, request_digest CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL, code VARCHAR(64),
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), ended_at DATETIME(6),
 input_tokens BIGINT, output_tokens BIGINT, total_tokens BIGINT,
 usage_snapshot TEXT, price_version VARCHAR(128), currency CHAR(3),
 reserved_cost BIGINT, actual_cost BIGINT, cost_quality VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,step_id) REFERENCES step(tenant_id,id), INDEX dispatch_task(tenant_id,task_id)
) ENGINE=InnoDB;
