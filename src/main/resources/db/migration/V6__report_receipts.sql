ALTER TABLE task ADD COLUMN report_time DATETIME(6);
ALTER TABLE artifact ADD COLUMN file_receipt TEXT,
 ADD COLUMN receipt_digest CHAR(64),
 ADD UNIQUE artifact_producer_receipt(tenant_id,task_id,producer_invocation,receipt_digest),
 DROP INDEX tenant_id;
