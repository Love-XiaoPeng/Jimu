-- Historical tasks have no material proof. They must fail closed, never adopt current materials.
ALTER TABLE task
 ADD COLUMN material_snapshot LONGTEXT,
 ADD COLUMN material_digest CHAR(64),
 ADD COLUMN material_system_digest CHAR(64),
 ADD COLUMN material_file_snapshot LONGTEXT,
 ADD COLUMN material_file_digest CHAR(64);
