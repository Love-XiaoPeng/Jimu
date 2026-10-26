package io.github.lovexiaopeng.jimu.kernel.question.service;

import io.github.lovexiaopeng.jimu.kernel.question.port.PendingQuestionMessages;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;

public interface PendingQuestionMessagesService {
  List<String> pending(Lease lease, PendingQuestionMessages port);
}
