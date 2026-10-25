package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEligibility;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionEligibilityService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Preserve the sorted lock chain, repeated snapshot reads and original short circuits. */
public final class SessionEligibilityServiceImpl implements SessionEligibilityService {
  public void access(SessionEligibility.Access records) {
    if (!records.session()) throw new TaskFailure(409, "SESSION_HISTORY_UNAVAILABLE");
    records.history();
    for (String name : records.capabilities().stream().sorted().toList()) {
      var descriptor = records.descriptor(name);
      var pin = records.pin(name);
      var installed = records.installation(name);
      if (!pin.version().equals(installed.version())
          || !pin.digest().equals(installed.digest())
          || pin.installationEpoch() != installed.epoch()
          || !pin.version().equals(descriptor.version())
          || !pin.digest().equals(descriptor.outputSha256()))
        throw new TaskFailure(403, "CAPABILITY_REVOKED");
    }
  }

  public void admission(SessionEligibility.Admission records) {
    if (!records.readEnabled()) throw new TaskFailure(403, "SESSION_READ_DISABLED");
    if (records.scope() && !records.scopeEnabled())
      throw new TaskFailure(403, "SESSION_SEARCH_DISABLED");
    if (records.search() && !records.searchEnabled())
      throw new TaskFailure(403, "SESSION_SEARCH_DISABLED");
    if (records.trace() && !records.traceEnabled())
      throw new TaskFailure(403, "SESSION_TRACE_DISABLED");
    records.history();
    if (records.trace()) records.requireTrace(records.snapshot());
    else if (records.search()) records.requireSearch(records.snapshot());
    else records.requireRead(records.snapshot());
  }
}
