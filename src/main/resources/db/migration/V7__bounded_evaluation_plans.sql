ALTER TABLE evaluation_budget
 DROP CHECK evaluation_budget_chk_1,
 DROP CHECK evaluation_budget_chk_2,
 ADD COLUMN plan_snapshot MEDIUMTEXT,
 ADD COLUMN plan_digest CHAR(64),
 ADD COLUMN phase_b_snapshot MEDIUMTEXT,
 ADD COLUMN phase_b_digest CHAR(64),
 ADD CHECK ((policy_version=1 AND max_requests BETWEEN 1 AND 20 AND max_cost_micros BETWEEN 1 AND 1000000 AND plan_snapshot IS NULL AND plan_digest IS NULL)
         OR (policy_version=2 AND max_requests BETWEEN 1 AND 76 AND max_cost_micros BETWEEN 1 AND 650000000 AND plan_snapshot IS NOT NULL AND plan_digest IS NOT NULL)),
 ADD CHECK (requests_used BETWEEN 0 AND max_requests AND cost_held>=0);
ALTER TABLE task
 ADD COLUMN evaluation_snapshot MEDIUMTEXT,
 ADD COLUMN evaluation_digest CHAR(64),
 ADD COLUMN evaluation_phase CHAR(1),
 ADD COLUMN evaluation_sample VARCHAR(8),
 ADD COLUMN evaluation_model VARCHAR(160);
