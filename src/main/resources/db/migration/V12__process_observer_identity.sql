-- Legacy receipts stay unknown: never infer their PID coordinate system from host_id.
ALTER TABLE worker_process
 ADD COLUMN boot_id VARCHAR(80) NULL,
 ADD COLUMN observer_pid_namespace VARCHAR(32) NULL;
