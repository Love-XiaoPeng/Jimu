package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Publication;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Upload;
import java.util.List;

public interface ReportPublicationService {
  List<Upload> stage(Lease lease, String invocation, List<ReportFile> files);

  void succeed(Lease lease, String invocation, List<Publication> publications, String output);
}
