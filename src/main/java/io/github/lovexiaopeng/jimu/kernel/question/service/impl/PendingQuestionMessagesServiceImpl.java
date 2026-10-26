package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.PendingQuestionMessages;
import io.github.lovexiaopeng.jimu.kernel.question.service.PendingQuestionMessagesService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;

/** Fence and materialize the original message values inside the lease transaction. */
public final class PendingQuestionMessagesServiceImpl implements PendingQuestionMessagesService {
  public List<String> pending(Lease lease, PendingQuestionMessages port) {
    return port.transaction(
        lease, () -> port.messages(port.fenced(lease, true)).values().stream().toList());
  }
}
