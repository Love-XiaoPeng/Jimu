-- Keep pre-existing occupancy visible even when the old unmetered copies exceed the
-- configured limit. Do not raise that limit or rewrite any retained request bytes.
ALTER TABLE tenant_budget ADD COLUMN media_checkpoint_held BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT tenant_media_checkpoint_nonnegative CHECK (media_checkpoint_held >= 0);
ALTER TABLE step ADD COLUMN request_cleanup_state VARCHAR(24) NOT NULL DEFAULT 'RETAINED',
 ADD COLUMN retained_request_digest CHAR(64) NULL;

INSERT INTO reservation(tenant_id,id,task_id,category,amount,actual,usage_quality,state)
 SELECT s.tenant_id,CONCAT(s.id,':media-checkpoint'),s.task_id,'MEDIA_CHECKPOINT',
 OCTET_LENGTH(s.request_snapshot),OCTET_LENGTH(s.request_snapshot),'KNOWN','SETTLED'
 FROM step s JOIN task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id
 WHERE t.record_version=3;
UPDATE tenant_budget b JOIN
 (SELECT tenant_id,SUM(amount) AS held FROM reservation WHERE category='MEDIA_CHECKPOINT'
  GROUP BY tenant_id) r ON r.tenant_id=b.tenant_id SET b.media_checkpoint_held=r.held;

-- Earlier cleanup could mark the task deleted while leaving checkpoint copies.
-- Re-enqueue those copies for the same terminal/quiescent retention check.
UPDATE task t SET media_cleanup_state='RETAINED'
 WHERE t.record_version=3 AND t.media_cleanup_state='DELETED' AND EXISTS
 (SELECT 1 FROM step s WHERE s.tenant_id=t.tenant_id AND s.task_id=t.id);
