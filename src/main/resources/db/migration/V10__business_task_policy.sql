-- Forward structure only. No production policy, scope or task is bound by migration.
ALTER TABLE task
 ADD COLUMN business_policy_snapshot LONGTEXT NULL CHECK
 (business_policy_snapshot IS NULL OR JSON_VALID(business_policy_snapshot)),
 ADD COLUMN business_policy_digest CHAR(64) NULL CHECK
 (business_policy_digest IS NULL OR REGEXP_LIKE(business_policy_digest,'^[0-9a-f]{64}$','c'));
