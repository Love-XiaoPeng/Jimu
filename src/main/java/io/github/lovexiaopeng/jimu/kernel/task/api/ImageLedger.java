package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;

/** Media operations use the same task lease, invocation and reservation authority. */
public interface ImageLedger {
  record Raw(TaskImages.Entry entry, byte[] bytes) {
    public Raw {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  record ObjectValue(ImagePort.Result result, String key) {}

  record ImageSource(
      ImagePort.Result result,
      String outputDigest,
      String stepId,
      String dispatchId,
      String configDigest,
      String requestBodyDigest,
      String responseDigest) {}

  record ReportEvidence(
      ExecutionLedger.ReportContext web, List<ImageSource> images, boolean localProtocol) {}

  ReportEvidence imageReportContext(ExecutionLedger.Lease lease, String compositionInvocation);

  TaskImages.Snapshot mediaSnapshot(ExecutionLedger.Lease lease);

  Raw imageInput(ExecutionLedger.Lease lease, String filePath);

  String reserveImage(ExecutionLedger.Lease lease, String invocationId, String inputId);

  void imageSucceeded(
      ExecutionLedger.Lease lease, String invocationId, String key, ImagePort.Result result);

  ObjectValue imageObject(ExecutionLedger.Lease lease, ImagePort.Result reference);

  List<ObjectValue> stepImages(ExecutionLedger.Lease lease, String stepId);

  void imageDecodeTimedOut(ExecutionLedger.Lease lease);
}
