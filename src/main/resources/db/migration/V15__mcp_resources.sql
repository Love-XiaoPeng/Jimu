ALTER TABLE task ADD COLUMN mcp_snapshot MEDIUMTEXT,
 ADD COLUMN mcp_digest CHAR(64),
 ADD CONSTRAINT task_mcp_pair CHECK ((mcp_snapshot IS NULL) = (mcp_digest IS NULL));

CREATE TABLE mcp_server (
 tenant_id VARCHAR(64) NOT NULL, server_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, epoch BIGINT NOT NULL,
 profile_snapshot TEXT NOT NULL, profile_digest CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,server_id), FOREIGN KEY(tenant_id) REFERENCES tenant_budget(tenant_id),
 CHECK (epoch > 0)
) ENGINE=InnoDB;
CREATE TABLE mcp_io (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, invocation_id CHAR(36) NOT NULL,
 ordinal INT NOT NULL, request_id VARCHAR(128), method VARCHAR(64) NOT NULL,
 kind VARCHAR(16) NOT NULL, phase VARCHAR(16) NOT NULL,
 request_digest CHAR(64) NOT NULL, error_code VARCHAR(64),
 PRIMARY KEY(tenant_id,task_id,invocation_id,ordinal),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,invocation_id) REFERENCES invocation(tenant_id,id),
 INDEX mcp_io_budget(tenant_id,task_id,kind)
) ENGINE=InnoDB;
CREATE TABLE mcp_result (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, root_invocation_id CHAR(36) NOT NULL,
 version INT NOT NULL DEFAULT 1, server VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 method VARCHAR(64) NOT NULL, binding_digest CHAR(64) NOT NULL,
 projection_digest CHAR(64) NOT NULL, body MEDIUMTEXT, body_digest CHAR(64) NOT NULL,
 byte_size BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL, state VARCHAR(16) NOT NULL,
 PRIMARY KEY(tenant_id,task_id,root_invocation_id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES task(tenant_id,id),
 FOREIGN KEY(tenant_id,root_invocation_id) REFERENCES invocation(tenant_id,id),
 INDEX mcp_result_cleanup(state,expires_at,tenant_id,task_id)
) ENGINE=InnoDB;
CREATE TABLE mcp_cursor (
 tenant_id VARCHAR(64) NOT NULL, task_id CHAR(36) NOT NULL, token_digest CHAR(64) COLLATE utf8mb4_bin NOT NULL,
 root_invocation_id CHAR(36) NOT NULL, page_index INT NOT NULL, query_digest CHAR(64) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,task_id,token_digest),
 FOREIGN KEY(tenant_id,task_id,root_invocation_id) REFERENCES mcp_result(tenant_id,task_id,root_invocation_id),
 INDEX mcp_cursor_root(tenant_id,task_id,root_invocation_id)
) ENGINE=InnoDB;
