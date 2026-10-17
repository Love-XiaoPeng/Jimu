-- Structure only. Operator binding is explicit; historical tasks remain unproven.
CREATE TABLE evaluation_execution_scope (
 tenant_id VARCHAR(64) NOT NULL,
 run_id VARCHAR(64) NOT NULL,
 record_version INT NOT NULL DEFAULT 1 CHECK (record_version=1),
 scope_snapshot LONGTEXT NOT NULL CHECK (JSON_VALID(scope_snapshot)),
 scope_digest CHAR(64) NOT NULL CHECK (REGEXP_LIKE(scope_digest,'^[0-9a-f]{64}$','c')),
 bound_at_utc DATETIME(6) NOT NULL,
 bound_by VARCHAR(160) NOT NULL,
 PRIMARY KEY (tenant_id,run_id),
 FOREIGN KEY (tenant_id,run_id) REFERENCES evaluation_budget(tenant_id,id)
) ENGINE=InnoDB;
ALTER TABLE task ADD COLUMN execution_scope_digest CHAR(64) NULL
 CHECK (execution_scope_digest IS NULL OR REGEXP_LIKE(execution_scope_digest,'^[0-9a-f]{64}$','c'));
