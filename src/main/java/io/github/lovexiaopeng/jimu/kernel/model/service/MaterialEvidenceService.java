package io.github.lovexiaopeng.jimu.kernel.model.service;

import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords.Material;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords.Row;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import java.util.Optional;

/** 冻结评测材料、原始文件收据、请求历史与图像报告证据用例。 */
public interface MaterialEvidenceService {
  Material material(Row task);

  void checkMaterials(Row task);

  Optional<String> evaluationFile(Lease lease);

  ReportFile initialFile(Row task);

  void bindEvaluationFile(Lease lease, ReportFile file);

  void materialBody(Row task, String request, int ordinal);

  ImageLedger.ReportEvidence imageReportContext(Lease lease, String invocation);

  ImageLedger.ReportEvidence imageReportContext(Row task, String invocation);
}
