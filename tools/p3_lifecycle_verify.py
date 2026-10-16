"""Task tool pins on real MySQL, without enabling public egress or paid dispatch."""
import argparse
import hashlib
import json
import os
import signal
from pathlib import Path

from p2_verify import Experiment, eventually


def verify(e):
    digest = e.sql("SELECT plugin_digest FROM installation WHERE tenant_id='tenant-a' AND plugin_id='web_fetch';")
    e.sql("UPDATE installation SET plugin_version='0.3.0' WHERE tenant_id='tenant-a';")
    public = e.submit("queued-public-pin")
    snapshot = e.sql("SELECT tools_snapshot FROM task WHERE id='" + public + "';")
    pin = json.loads(snapshot)
    e.assert_that(pin == {"schemaVersion": 1, "pins": {"web_fetch": {
        "version": "0.3.0", "digest": digest, "installationEpoch": 1}}},
        "public plugin pinned at acceptance before any worker or model", snapshot=pin)
    e.assert_that(e.sql("SELECT COUNT(*) FROM step WHERE task_id='" + public + "';") == "0",
                  "queued public snapshot does not require a model response")
    e.assert_that(e.sql("SELECT tools_digest FROM task WHERE id='" + public + "';") == hashlib.sha256(snapshot.encode()).hexdigest(),
                  "accepted tool snapshot digest matches persisted bytes")
    e.sql("UPDATE installation SET plugin_version='0.2.0' WHERE tenant_id='tenant-a';")
    e.stop(e.control)
    e.control = e.start("snapshot-control-restarted", "control")
    e.assert_that(e.submit("queued-public-pin") == public and e.sql("SELECT tools_snapshot FROM task WHERE id='" + public + "';") == snapshot,
                  "control restart and idempotent retry retain original public pin despite installation change")
    other = e.submit("isolated-fixture-pin", subject="b")
    e.assert_that(json.loads(e.sql("SELECT tools_snapshot FROM task WHERE id='" + other + "';"))["pins"]["web_fetch"]["version"] == "0.2.0",
                  "other tenant keeps its own fixture pin")
    e.assert_that(e.http("GET", "/api/tasks/" + public, subject="b")[0] == 404 and
                  e.http("GET", "/api/tasks/" + other)[0] == 404,
                  "task snapshots do not grant cross tenant task visibility")
    worker = e.start("public-pin-unsupported-worker", "worker")
    e.state(public, "FAILED")
    eventually(lambda: e.sql("SELECT state FROM task WHERE id='" + other + "';") == "SUCCEEDED",
               "other tenant fixture completes")
    e.assert_that(e.http("GET", "/api/tasks/" + other, subject="b")[1]["state"] == "SUCCEEDED",
                  "owner of the other tenant can read its completed task")
    e.assert_that(e.sql("SELECT code FROM task WHERE id='" + public + "';") == "PLUGIN_PIN_MISMATCH" and
                  e.fixture.models.get("queued-public-pin", 0) == 0 and e.fixture.tools.get("/page/queued-public-pin", 0) == 0,
                  "worker lacking snapshotted public implementation fails before any model or fetch")
    e.stop(worker)

    task = e.submit("pin-response-drift")
    worker = e.start("pin-response-drift-worker", "worker", "M1")
    e.barrier(worker)
    e.sql("UPDATE installation SET plugin_version='0.3.0' WHERE tenant_id='tenant-a';")
    (worker["folder"] / "release").touch()
    e.state(task, "FAILED")
    e.assert_that(e.sql("SELECT plugin_version FROM invocation WHERE task_id='" + task + "';") == "0.2.0" and
                  e.sql("SELECT code FROM task WHERE id='" + task + "';") == "PLUGIN_PIN_MISMATCH" and
                  e.fixture.tools.get("/page/pin-response-drift", 0) == 0,
                  "response copies accepted pin rather than current installation and rejects drift before effect")
    e.stop(worker)
    e.sql("UPDATE installation SET plugin_version='0.2.0' WHERE tenant_id='tenant-a';")

    task = e.submit("cancel-unknown-receipt")
    worker = e.start("cancel-unknown-receipt-worker", "worker", "T0")
    e.barrier(worker)
    e.assert_that(e.http("POST", "/api/tasks/" + task + "/cancel")[0] == 202,
                  "cancel accepted while an admitted effect is not yet resolved")
    # The paused child represents an uninterruptible operation. Inject the ledger state that
    # uncertain() commits; this is a supervisor/receipt test, not a public DNS end-to-end test.
    os.kill(e.control["process"].pid, signal.SIGSTOP)
    try:
        e.sql("UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE task_id='" + task + "';"
              "UPDATE reservation SET state='HELD_UNKNOWN' WHERE task_id='" + task + "' AND category='TOOL';"
              "UPDATE task SET state='NEEDS_RECONCILIATION',code='EFFECT_OUTCOME_UNKNOWN' WHERE id='" + task + "';")
        e.assert_that(worker["process"].poll() is None and
                      e.sql("SELECT quiescent FROM task_attempt WHERE task_id='" + task + "';") == "0" and
                      e.sql("SELECT state FROM reservation WHERE id='" + task + ":run';") == "RESERVED",
                      "unknown outcome retains live attempt and run reservation after cancellation",
                      faultInjection="SQL unknown outcome after real cancel", workerPid=worker["process"].pid)
    finally:
        os.kill(e.control["process"].pid, signal.SIGCONT)
    eventually(lambda: worker["process"].poll() is not None,
               "cancel intent still stops nonquiescent worker after unknown outcome", 12)
    eventually(lambda: e.sql("SELECT state FROM reservation WHERE id='" + task + ":run';") == "RELEASED",
               "supervisor confirms stopped unknown worker before releasing run", 12)
    e.assert_that(e.sql("SELECT state FROM task WHERE id='" + task + "';") == "NEEDS_RECONCILIATION" and
                  e.sql("SELECT state FROM reservation WHERE task_id='" + task + "' AND category='TOOL';") == "HELD_UNKNOWN" and
                  e.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='" + task + "' AND quiescent=FALSE;") == "0" and
                  e.fixture.tools.get("/page/cancel-unknown-receipt", 0) == 0,
                  "process exit resolves quiescence without erasing unknown effect or starting transport",
                  exitCode=worker["process"].returncode)

    for goal, mutation, code in [
        ("pin-corrupt", "tools_digest='" + "0" * 64 + "'", "SNAPSHOT_CORRUPT"),
        ("pin-future", "tools_snapshot=JSON_SET(tools_snapshot,'$.schemaVersion',99)", "PLUGIN_SNAPSHOT_UNSUPPORTED"),
    ]:
        task = e.submit(goal)
        e.sql("UPDATE task SET " + mutation + " WHERE id='" + task + "';")
        if goal == "pin-future":
            e.sql("UPDATE task SET tools_digest=SHA2(tools_snapshot,256) WHERE id='" + task + "';")
        worker = e.start(goal + "-worker", "worker")
        e.state(task, "NEEDS_RECONCILIATION" if code == "SNAPSHOT_CORRUPT" else "FAILED")
        e.assert_that(e.sql("SELECT code FROM task WHERE id='" + task + "';") == code and e.fixture.models.get(goal, 0) == 0,
                      goal + " rejected before model dispatch")
        e.stop(worker)

    # Recreate a real V4 cache and an unstarted V4 task. Do not manufacture a pin for the latter.
    cached = e.submit("snapshot-v4-cache")
    worker = e.start("snapshot-v4-writer", "worker", "T1")
    e.barrier(worker)
    e.stop(worker, kill=True)
    e.assert_that(e.fixture.tools.get("/page/snapshot-v4-cache", 0) == 1,
                  "old successful invocation exists before migration")
    queued = e.submit("snapshot-v4-queued")
    e.stop(e.control)
    e.sql("ALTER TABLE task DROP COLUMN material_snapshot,DROP COLUMN material_digest,DROP COLUMN material_system_digest,DROP COLUMN material_file_snapshot,DROP COLUMN material_file_digest; DELETE FROM flyway_schema_history WHERE version='8';")
    e.sql("ALTER TABLE task DROP COLUMN evaluation_snapshot,DROP COLUMN evaluation_digest,DROP COLUMN evaluation_phase,DROP COLUMN evaluation_sample,DROP COLUMN evaluation_model; ALTER TABLE evaluation_budget DROP CHECK evaluation_budget_chk_1,DROP CHECK evaluation_budget_chk_2,DROP COLUMN plan_snapshot,DROP COLUMN plan_digest,DROP COLUMN phase_b_snapshot,DROP COLUMN phase_b_digest,ADD CHECK(max_requests BETWEEN 1 AND 20 AND requests_used BETWEEN 0 AND max_requests),ADD CHECK(max_cost_micros BETWEEN 1 AND 1000000 AND cost_held>=0); DELETE FROM flyway_schema_history WHERE version='7';")
    e.sql("ALTER TABLE task DROP COLUMN report_time; ALTER TABLE artifact DROP COLUMN file_receipt,DROP COLUMN receipt_digest,ADD UNIQUE(tenant_id,task_id,producer_invocation),DROP INDEX artifact_producer_receipt; DELETE FROM flyway_schema_history WHERE version='6';")
    e.sql("ALTER TABLE task DROP COLUMN tools_snapshot,DROP COLUMN tools_digest; DELETE FROM flyway_schema_history WHERE version='5';")
    e.control = e.start("snapshot-v5-upgrade-control", "control")
    restored = e.sql("SELECT tools_snapshot FROM task WHERE id='" + cached + "';")
    e.assert_that(json.loads(restored)["pins"]["web_fetch"] == {"version": "0.2.0", "digest": digest, "installationEpoch": 1},
                  "V5 migration reconstructs a legacy cached pin from unanimous invocation receipts")
    e.assert_that(e.sql("SELECT tools_digest=SHA2(tools_snapshot,256) FROM task WHERE id='" + cached + "';") == "1",
                  "migrated snapshot digest validates original stored bytes")
    e.assert_that(e.sql("SELECT tools_snapshot IS NULL FROM task WHERE id='" + queued + "';") == "1",
                  "migration leaves historical unstarted pin explicitly unknown")
    e.expire(cached)
    worker = e.start("snapshot-v5-reader", "worker")
    e.state(cached, "SUCCEEDED")
    e.state(queued, "FAILED")
    e.assert_that(e.fixture.tools.get("/page/snapshot-v4-cache", 0) == 1,
                  "new worker resumes migrated successful cache without replaying fetch")
    e.assert_that(e.sql("SELECT code FROM task WHERE id='" + queued + "';") == "PLUGIN_SNAPSHOT_UNAVAILABLE" and e.fixture.models.get("snapshot-v4-queued", 0) == 0,
                  "unprovable legacy queued pin fails closed without model request")
    e.stop(worker)
    e.assert_that(e.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=TRUE;") == "1",
                  "V5 migration applied exactly once")
    (e.evidence / "task-tool-snapshots.tsv").write_text(e.sql(
        "SELECT tenant_id,id,state,code,capabilities,installation_epoch,tools_snapshot,tools_digest FROM task ORDER BY tenant_id,id;"))


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt("Snapshot test supervisor terminated")
    signal.signal(signal.SIGTERM, terminate)
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument("evidence", type=Path)
    args = parser.parse_args()
    experiment = Experiment(args.jar, args.evidence)
    try:
        experiment.prepare()
        verify(experiment)
    finally:
        experiment.cleanup()


if __name__ == "__main__":
    main()
