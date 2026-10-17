"""R5 real worker/supervisor order tests. Unknown outcome is explicitly SQL injected at T0."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import signal
import sys
import threading
import uuid

sys.dont_write_bytecode = True
from p2_verify import Experiment, eventually


def verify(e):
    facts = []
    # Keep HTTP responsive while the worker-host supervisor is paused. This second, real
    # control owns a different host id and therefore cannot stop the parked worker itself.
    api = e.start("r5-other-host-api", "control", extra={"jimu.host-id": str(uuid.uuid4())})

    def unknown(task):
        # Same lock order as the actual ledger, with a transaction for the injected facts.
        e.sql("START TRANSACTION; SELECT tenant_id FROM tenant_budget WHERE tenant_id='tenant-a' FOR UPDATE;"
              "UPDATE task SET state='NEEDS_RECONCILIATION',code='EFFECT_OUTCOME_UNKNOWN' WHERE id='" + task + "';"
              "UPDATE invocation SET state='OUTCOME_UNKNOWN' WHERE task_id='" + task + "';"
              "UPDATE reservation SET state='HELD_UNKNOWN',usage_quality='UNKNOWN' WHERE task_id='" + task + "' AND category='TOOL'; COMMIT;")

    def state(task):
        return e.sql("SELECT t.state,t.code,COALESCE(CAST(t.cancel_requested_at AS CHAR),'NULL'),"
                     "a.quiescent,r.state FROM task t JOIN task_attempt a ON a.tenant_id=t.tenant_id AND a.task_id=t.id "
                     "JOIN reservation r ON r.tenant_id=t.tenant_id AND r.id=CONCAT(t.id,':run') WHERE t.id='" + task + "';")

    for index, order in enumerate(["cancel-first", "unknown-first", "race", "race", "race"]):
        goal = "r5-" + order + "-" + str(index)
        task = e.submit(goal)
        worker = e.start(goal + "-worker", "worker", "T0")
        e.barrier(worker)
        os.kill(e.control["process"].pid, signal.SIGSTOP)
        try:
            if order == "cancel-first":
                status, body = e.http("POST", "/api/tasks/" + task + "/cancel", instance=api)
                e.assert_that(status == 202, "R5 cancel-first acknowledges running cancellation", case=goal)
                unknown(task)
            elif order == "unknown-first":
                unknown(task)
                status, body = e.http("POST", "/api/tasks/" + task + "/cancel", instance=api)
                e.assert_that(status == 200 and body["state"] == "NEEDS_RECONCILIATION" and body["cancelRequestedAt"] is not None,
                              "R5 unknown-first terminal response retains persistent stop intent", case=goal)
            else:
                gate = threading.Barrier(2)
                def inject():
                    gate.wait(timeout=5)
                    unknown(task)
                def cancel():
                    gate.wait(timeout=5)
                    return e.http("POST", "/api/tasks/" + task + "/cancel", instance=api)
                with ThreadPoolExecutor(max_workers=2) as pool:
                    mutation, cancellation = pool.submit(inject), pool.submit(cancel)
                    mutation.result(timeout=15)
                    status, body = cancellation.result(timeout=15)
                e.assert_that(status in (200, 202), "R5 concurrent unknown and cancellation both commit", case=goal, cancelState=body["state"])
            before = state(task)
            e.assert_that(worker["process"].poll() is None and before.split("\t") == ["NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN", before.split("\t")[2], "0", "RESERVED"]
                          and before.split("\t")[2] != "NULL",
                          "R5 stop intent never claims process quiescence or releases run early", case=goal, ledger=before)
            e.assert_that(e.sql("SELECT state FROM reservation WHERE task_id='" + task + "' AND category='TOOL';") == "HELD_UNKNOWN",
                          "R5 unknown fee remains held before process stop", case=goal)
            timestamp = before.split("\t")[2]
            with ThreadPoolExecutor(max_workers=4) as pool:
                repeats = list(pool.map(lambda _: e.http("POST", "/api/tasks/" + task + "/cancel", instance=api), range(8)))
            e.assert_that(all(code == 200 and value["state"] == "NEEDS_RECONCILIATION" for code, value in repeats)
                          and state(task).split("\t")[2] == timestamp,
                          "R5 repeated concurrent cancellation is idempotent and does not restart grace", case=goal)
            e.assert_that(e.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND fact_id='cancel';") == "1",
                          "R5 exactly one durable cancellation event", case=goal)
            facts.append({"case": goal, "order": order, "task": task, "pid": worker["process"].pid,
                          "cancelHttp": status, "cancelBody": body, "beforeStop": before,
                          "injection": "SQL unknown outcome behind real worker T0 barrier"})
        finally:
            os.kill(e.control["process"].pid, signal.SIGCONT)
        eventually(lambda: worker["process"].poll() is not None, "R5 supervisor stops " + goal, 12)
        eventually(lambda: state(task).endswith("\t1\tRELEASED"), "R5 confirmed process exit releases run " + goal, 12)
        after = state(task)
        e.assert_that(after.split("\t")[:2] == ["NEEDS_RECONCILIATION", "EFFECT_OUTCOME_UNKNOWN"]
                      and e.sql("SELECT state FROM reservation WHERE task_id='" + task + "' AND category='TOOL';") == "HELD_UNKNOWN",
                      "R5 stopped process preserves unknown outcome and unknown tool fee", case=goal, ledger=after, exitCode=worker["process"].returncode)
        e.assert_that(e.fixture.tools.get("/page/" + goal, 0) == 0,
                      "R5 parked invocation never reaches transport", case=goal)
        facts[-1].update(afterStop=after, workerAlive=False, exitCode=worker["process"].returncode,
                         toolCalls=e.fixture.tools.get("/page/" + goal, 0))

    # Unknown and already actually stopped: cancellation must remain a no-op.
    task = e.submit("r5-quiescent")
    worker = e.start("r5-quiescent-worker", "worker", "T0")
    e.barrier(worker)
    unknown(task)
    e.stop(worker, kill=True)
    eventually(lambda: state(task).endswith("\t1\tRELEASED"), "R5 quiescent process receipt", 12)
    before = state(task)
    status, body = e.http("POST", "/api/tasks/" + task + "/cancel")
    e.assert_that(status == 200 and body["state"] == "NEEDS_RECONCILIATION" and body["cancelRequestedAt"] is None and state(task) == before,
                  "R5 already quiescent unknown task gains no stop intent", ledger=before)
    e.assert_that(e.sql("SELECT COUNT(*) FROM event WHERE task_id='" + task + "' AND fact_id='cancel';") == "0"
                  and e.sql("SELECT state FROM reservation WHERE task_id='" + task + "' AND category='TOOL';") == "HELD_UNKNOWN",
                  "R5 quiescent cancellation creates no event and preserves unknown cost")
    facts.append({"case": "already-quiescent", "task": task, "cancelHttp": status, "cancelBody": body,
                  "beforeStop": before, "afterStop": state(task), "workerAlive": False})

    # Ordinary completed work retains the existing no-op terminal cancellation behavior.
    task = e.submit("r5-successful")
    worker = e.start("r5-successful-worker", "worker")
    e.state(task, "SUCCEEDED")
    eventually(lambda: e.sql("SELECT COUNT(*) FROM task_attempt WHERE task_id='" + task + "' AND quiescent=FALSE;") == "0", "R5 successful worker quiescent")
    before = e.http("GET", "/api/tasks/" + task)[1]
    status, body = e.http("POST", "/api/tasks/" + task + "/cancel")
    e.assert_that(status == 200 and body == before and body["cancelRequestedAt"] is None,
                  "R5 successful quiescent task cancellation remains a no-op")
    e.stop(worker)
    e.stop(api)
    (e.evidence / "cancel-order-facts.json").write_text(json.dumps(facts, indent=2))
    (e.evidence / "cancel-intents.tsv").write_text(e.sql("SELECT tenant_id,id,state,code,cancel_requested_at FROM task ORDER BY id;"))


def main():
    def terminate(signum, frame):
        raise KeyboardInterrupt("Cancel order test terminated")
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
