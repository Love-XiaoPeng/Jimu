package io.github.lovexiaopeng.jimu.kernel.operator.service.impl;

import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultProofCodec;
import io.github.lovexiaopeng.jimu.kernel.operator.port.WebResultRecords;
import io.github.lovexiaopeng.jimu.kernel.operator.service.WebResultAssessmentService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.Map;

/** 保留原NOT_FOUND→缺失证明→无效绑定拒绝顺序，在一致只读事务中评估。 */
public final class WebResultAssessmentServiceImpl implements WebResultAssessmentService {
  private final WebResultRecords records;
  private final WebResultProofCodec codec;
  private final LedgerUnitOfWork transactions;

  public WebResultAssessmentServiceImpl(
      WebResultRecords records, WebResultProofCodec codec, LedgerUnitOfWork transactions) {
    this.records = records;
    this.codec = codec;
    this.transactions = transactions;
  }

  public Map<String, Object> assess(String tenant, String task, String id) {
    return transactions.required(
        () -> {
          var tasks = records.tasks(tenant, task);
          var invocations = records.invocations(tenant, task, id);
          if (tasks.size() != 1 || invocations.size() != 1) throw new TaskFailure(404, "NOT_FOUND");
          var invocation = invocations.getFirst();
          var proofs = records.proofs(tenant, task, "web-result:" + id);
          String output = invocation.outputSnapshot();
          if (proofs.size() != 1
              || output == null
              || !codec.hash(output).equals(invocation.outputDigest()))
            throw new TaskFailure(409, "WEB_RESULT_PROOF_MISSING");
          var steps = records.steps(tenant, task, invocation.stepId());
          if (steps.size() != 1
              || !codec
                  .receipt(tasks.getFirst(), steps.getFirst(), invocation, output)
                  .equals(proofs.getFirst()))
            throw new TaskFailure(409, "WEB_RESULT_PROOF_INVALID");
          return Map.of(
              "taskId",
              task,
              "invocationId",
              id,
              "state",
              invocation.state(),
              "outputDigest",
              invocation.outputDigest(),
              "receiptDigest",
              proofs.getFirst(),
              "assessment",
              "PROVEN_RESULT_ONLY_NO_RESUME_OR_SETTLEMENT",
              "readOnly",
              true);
        });
  }
}
