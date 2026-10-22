ALTER TABLE tenant_budget
 ADD COLUMN question_tasks_held INT NOT NULL DEFAULT 0,
 ADD COLUMN question_tasks_limit INT NOT NULL DEFAULT 16,
 ADD CONSTRAINT question_capacity CHECK (question_tasks_held >= 0 AND question_tasks_held <= question_tasks_limit);

ALTER TABLE task
 ADD COLUMN question_binding TEXT,
 ADD COLUMN question_binding_digest CHAR(64),
 ADD COLUMN waiting_question_id CHAR(36),
 ADD COLUMN question_continuation_step_id CHAR(36),
 ADD COLUMN question_cleanup_state VARCHAR(24),
 ADD COLUMN question_retained_digests TEXT;

CREATE TABLE user_question (
 tenant_id VARCHAR(64) NOT NULL, id CHAR(36) NOT NULL, task_id CHAR(36) NOT NULL,
 owner_actor_id VARCHAR(64) NOT NULL, invocation_id CHAR(36) NOT NULL, ordinal INT NOT NULL,
 record_version INT NOT NULL DEFAULT 1, question_version INT NOT NULL DEFAULT 1,
 revision INT NOT NULL DEFAULT 1, binding_digest CHAR(64) NOT NULL,
 mode VARCHAR(16) NOT NULL, effective_timeout INT, phase VARCHAR(24) NOT NULL,
 request_snapshot TEXT NOT NULL, request_digest CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), foreground_due_at DATETIME(6),
 answer_expires_at DATETIME(6) NOT NULL, retain_until DATETIME(6) NOT NULL, close_code VARCHAR(64),
 answer_id CHAR(36), answered_actor_id VARCHAR(64), answered_at DATETIME(6), accepted_seq INT,
 answer_snapshot TEXT, answer_digest CHAR(64), answer_key VARCHAR(128) COLLATE utf8mb4_bin,
 answer_request_digest CHAR(64), answer_source_revision INT, receipt_snapshot TEXT,
 delivery_state VARCHAR(24) NOT NULL DEFAULT 'NONE', delivery_step_id CHAR(36),
 retained_digests TEXT,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,task_id,invocation_id),
 UNIQUE(tenant_id,task_id,ordinal), UNIQUE(tenant_id,task_id,owner_actor_id,answer_key),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 INDEX question_task(tenant_id,task_id,ordinal),
 INDEX question_due(phase,foreground_due_at,tenant_id,task_id),
 INDEX question_expiry(phase,answer_expires_at,tenant_id,task_id),
 INDEX question_delivery(tenant_id,task_id,delivery_state,ordinal),
 INDEX question_cleanup(retain_until,tenant_id,task_id),
 CHECK (ordinal BETWEEN 1 AND 8 AND revision >= 1),
 CHECK (phase IN ('OPEN','CONTINUED','ANSWERED','CLOSED')),
 CHECK (delivery_state IN ('NONE','PENDING','CHECKPOINTED','DISCARDED')),
 CHECK ((answer_id IS NULL AND answered_actor_id IS NULL AND answered_at IS NULL AND answer_key IS NULL AND delivery_state='NONE')
     OR (answer_id IS NOT NULL AND answered_actor_id IS NOT NULL AND answered_at IS NOT NULL AND answer_key IS NOT NULL AND delivery_state<>'NONE'))
) ENGINE=InnoDB;
