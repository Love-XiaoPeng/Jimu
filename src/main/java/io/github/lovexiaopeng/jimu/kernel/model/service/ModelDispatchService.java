package io.github.lovexiaopeng.jimu.kernel.model.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Call;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.model.api.ModelUsage;
import java.util.List;

public interface ModelDispatchService {
  ModelSnapshot snapshot(Lease lease);

  ToolSnapshot tools(Lease lease);

  String dispatch(Lease lease, String step);

  void responded(
      Lease lease,
      String step,
      String dispatch,
      String response,
      List<Call> calls,
      Integer tokens,
      ModelUsage usage);

  void failed(Lease lease, String dispatch, String code, ModelUsage usage);
}
