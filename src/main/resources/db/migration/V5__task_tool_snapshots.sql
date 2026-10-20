ALTER TABLE task
 ADD COLUMN tools_snapshot MEDIUMTEXT,
 ADD COLUMN tools_digest CHAR(64);

-- Older tasks did not record pins until the first response. Recover only pins proven by
-- unanimous persisted invocations. Never guess a queued task's historical pin from today's
-- installation. Incomplete legacy snapshots stay NULL and fail closed on execution.
UPDATE task t JOIN (
 SELECT tenant_id,task_id,
        JSON_OBJECT('schemaVersion',1,'pins',JSON_OBJECTAGG(tool,
          JSON_OBJECT('version',plugin_version,'digest',plugin_digest,'installationEpoch',installation_epoch))) AS snapshot
 FROM (
   SELECT tenant_id,task_id,tool,MIN(plugin_version) AS plugin_version,
          MIN(plugin_digest) AS plugin_digest,MIN(installation_epoch) AS installation_epoch
   FROM invocation
   GROUP BY tenant_id,task_id,tool
   HAVING COUNT(DISTINCT plugin_version)=1 AND COUNT(DISTINCT plugin_digest)=1
      AND COUNT(DISTINCT installation_epoch)=1
 ) proven GROUP BY tenant_id,task_id
) pins ON t.tenant_id=pins.tenant_id AND t.id=pins.task_id
SET t.tools_snapshot=pins.snapshot
WHERE JSON_LENGTH(JSON_EXTRACT(pins.snapshot,'$.pins'))=1+LENGTH(t.capabilities)-LENGTH(REPLACE(t.capabilities,',',''));

UPDATE task SET tools_digest=SHA2(tools_snapshot,256) WHERE tools_snapshot IS NOT NULL;
