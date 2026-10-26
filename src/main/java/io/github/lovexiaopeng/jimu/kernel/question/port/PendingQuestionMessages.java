package io.github.lovexiaopeng.jimu.kernel.question.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Lazy, individual operations of the public pending-message entry. */
public interface PendingQuestionMessages {
  List<String> transaction(Lease lease, Supplier<List<String>> work);

  Map<String, Object> fenced(Lease lease, boolean admission);

  Map<String, String> messages(Map<String, Object> task);
}
