CREATE TABLE worker_process (
 id CHAR(36) PRIMARY KEY, host_id CHAR(36) NOT NULL, pid BIGINT NOT NULL, start_ticks BIGINT NOT NULL,
 stopped BOOLEAN NOT NULL DEFAULT FALSE, registered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX worker_host(host_id,stopped,id)
) ENGINE=InnoDB;
