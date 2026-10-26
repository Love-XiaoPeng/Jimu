package io.github.lovexiaopeng.jimu.kernel.invocation.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;

public interface WebFetchService {
  void observe(Lease lease, String invocation, WebFetchObservation observation);

  void preserve(Lease lease, String invocation, String output);
}
