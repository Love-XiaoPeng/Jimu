package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionHistoryPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionCheckpointPreparation;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionCheckpointPreparationService;
import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.nio.charset.StandardCharsets;

/** 保留大小、末项消息、context、history 和 scope 的原短路及重复读取顺序。 */
public final class SessionCheckpointPreparationServiceImpl
    implements SessionCheckpointPreparationService {
  private final SnapshotDigest digest;

  public SessionCheckpointPreparationServiceImpl(SnapshotDigest digest) {
    this.digest = digest;
  }

  public void prepare(SessionCheckpointPreparation storage, String request) {
    var wire = storage.decode(request);
    if (bytes(request) > SessionHistoryPolicyV1.REQUEST_SNAPSHOT_BYTES
        || bytes(wire.requestBody()) > SessionHistoryPolicyV1.WIRE_REQUEST_BYTES)
      throw new TaskFailure(429, SessionErrorCode.SESSION_HISTORY_LIMIT);
    if (wire.messagesEmpty()
        || !wire.lastRole().equals("user")
        || !wire.lastContent().equals(storage.context()))
      throw new TaskFailure(409, SessionErrorCode.SESSION_BOUNDARY_CONFLICT);
    var history = storage.lockHistory();
    storage.updateStep(
        history.nextSeq() - 1, digest.hash(wire.lastContent()), history.bindingDigest());
    if (storage.scopeTask()) {
      String context = wire.lastContent();
      var scope = storage.requireScope();
      storage.insertScope(context, scope);
      storage.body(bytes(context));
    }
    storage.body(bytes(request));
  }

  private static int bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }
}
