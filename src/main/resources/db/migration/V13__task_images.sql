ALTER TABLE task ADD COLUMN media_snapshot MEDIUMTEXT NULL,
 ADD COLUMN media_digest CHAR(64) NULL, ADD COLUMN media_cleanup_state VARCHAR(24) NULL,
 ADD CONSTRAINT task_media_pair CHECK ((media_snapshot IS NULL AND media_digest IS NULL) OR
 (record_version=3 AND JSON_VALID(media_snapshot) AND media_digest REGEXP '^[a-f0-9]{64}$'));
ALTER TABLE invocation ADD UNIQUE KEY invocation_task_identity(tenant_id,task_id,id);
CREATE TABLE task_image_input (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, input_id CHAR(36) NOT NULL,
 logical_path VARCHAR(512) COLLATE utf8mb4_bin NOT NULL,
 raw_sha256 CHAR(64) NOT NULL, raw_size BIGINT NOT NULL, raw_base64 MEDIUMTEXT NULL,
 expires_at DATETIME(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)+INTERVAL 7 DAY), revoked_at DATETIME(6),
 PRIMARY KEY(tenant_id,input_id), UNIQUE(tenant_id,task_id,logical_path), UNIQUE(tenant_id,task_id,input_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 CHECK(raw_size BETWEEN 1 AND 1048576)
) ENGINE=InnoDB;
CREATE TABLE task_image_object (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, image_id CHAR(36) NOT NULL,
 producer_invocation CHAR(36) NOT NULL, input_id CHAR(36) NOT NULL,
 normalizer_version VARCHAR(32) NOT NULL, snapshot_key VARCHAR(160), sha256 CHAR(64), size BIGINT,
 width INT, height INT, original_width INT, original_height INT,
 phase VARCHAR(24) NOT NULL, cleanup_state VARCHAR(24) NOT NULL DEFAULT 'RETAINED',
 expires_at DATETIME(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)+INTERVAL 7 DAY),
 PRIMARY KEY(tenant_id,image_id), UNIQUE(tenant_id,producer_invocation), INDEX(tenant_id,task_id),
 INDEX image_cleanup(phase,expires_at),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id,input_id) REFERENCES task_image_input(tenant_id,task_id,input_id),
 FOREIGN KEY(tenant_id,task_id,producer_invocation) REFERENCES invocation(tenant_id,task_id,id)
) ENGINE=InnoDB;
