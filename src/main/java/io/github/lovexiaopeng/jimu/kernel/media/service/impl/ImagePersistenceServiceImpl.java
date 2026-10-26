package io.github.lovexiaopeng.jimu.kernel.media.service.impl;

import io.github.lovexiaopeng.jimu.kernel.media.port.ImageRecords;
import io.github.lovexiaopeng.jimu.kernel.media.service.ImagePersistenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;

/** Complete original image integrity and lifecycle orchestration in the caller's UoW. */
public final class ImagePersistenceServiceImpl implements ImagePersistenceService {
  private final ImageRecords records;

  public ImagePersistenceServiceImpl(ImageRecords records) {
    this.records = records;
  }

  @Override
  public TaskImages.Snapshot mediaSnapshot(Lease lease) {
    return records.leaseTransaction(lease, () -> snapshot(records.fenced(lease, true)));
  }

  @Override
  public ImageLedger.Raw imageInput(Lease lease, String path) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          records.requireImages(task);
          return raw(task, path);
        });
  }

  @Override
  public String reserveImage(Lease lease, String invocation, String inputId) {
    return records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          // fenced holds evaluation -> budget -> task; only re-read our already locked budget.
          var budget = records.budget(lease.tenant());
          if (1048576
              > budget.storageLimit() - (budget.storageHeld() + budget.mediaCheckpointHeld()))
            throw new TaskFailure(429, "STORAGE_BUDGET_EXCEEDED");
          String image = records.id();
          reserve(task, invocation, image, inputId);
          records.holdImageStorage(lease.tenant());
          records.reserveStorage(
              lease.tenant(),
              lease.taskId(),
              image,
              null,
              "MEDIA_STORAGE",
              1048576,
              "RESERVED",
              "KNOWN");
          return image;
        });
  }

  @Override
  public void imageSucceeded(Lease lease, String invocation, String key, ImagePort.Result result) {
    records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, true);
          succeeded(task, invocation, key, result);
          records.settleImageReservation(
              result.image().size(), lease.tenant(), result.image().imageId());
          records.releaseImageStorage(1048576 - result.image().size(), lease.tenant());
          records.toolSucceeded(lease, invocation, records.json(result));
          return null;
        });
  }

  @Override
  public ImageLedger.ObjectValue imageObject(Lease lease, ImagePort.Result ref) {
    return records.leaseTransaction(lease, () -> object(records.fenced(lease, true), ref));
  }

  @Override
  public List<ImageLedger.ObjectValue> stepImages(Lease lease, String step) {
    return records.leaseTransaction(lease, () -> step(records.fenced(lease, true), step));
  }

  @Override
  public void imageDecodeTimedOut(Lease lease) {
    records.leaseTransaction(
        lease,
        () -> {
          var task = records.fenced(lease, false);
          records.cancelImageDecode(lease.tenant(), lease.taskId());
          records.event(
              task, "image-decode-timeout", "task.cancel_requested", "IMAGE_DECODE_TIMEOUT");
          return null;
        });
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  public void insert(
      Tasks.Identity actor,
      String task,
      List<TaskImages.Input> inputs,
      List<TaskImages.Descriptor> descriptors) {
    var entries = new java.util.ArrayList<TaskImages.Entry>();
    for (int i = 0; i < inputs.size(); i++) {
      var input = inputs.get(i);
      var descriptor = descriptors.get(i);
      String id = records.id();
      records.insertInput(
          actor.tenant(),
          task,
          id,
          input.filePath(),
          descriptor.sha256(),
          descriptor.size(),
          input.dataBase64());
      entries.add(
          new TaskImages.Entry(id, descriptor.filePath(), descriptor.sha256(), descriptor.size()));
    }
    String snapshot = records.json(TaskImages.snapshot(entries));
    records.saveMediaSnapshot(snapshot, records.hash(snapshot), actor.tenant(), task);
  }

  public TaskImages.Snapshot snapshot(ImageRecords.Task task) {
    String raw = task.mediaSnapshot();
    if (task.recordVersion() != 3 || raw == null || !records.hash(raw).equals(task.mediaDigest()))
      throw corrupt();
    var value = records.readSnapshot(raw);
    if (!value.equals(TaskImages.snapshot(value.entries()))) throw corrupt();
    value.entries().forEach(e -> input(task, e.inputId()));
    var request = records.readRequest(task.requestSnapshot());
    var expected =
        value.entries().stream()
            .map(e -> new TaskImages.Descriptor(e.filePath(), e.sha256(), e.size()))
            .toList();
    if (!java.util.Objects.equals(
        expected, request.images() == null ? List.of() : request.images())) throw corrupt();
    var rows = records.selectInputIds(task.tenantId(), task.id());
    if (rows.size() != value.entries().size()
        || !rows.stream()
            .map(r -> r.inputId())
            .collect(java.util.stream.Collectors.toSet())
            .equals(
                value.entries().stream()
                    .map(TaskImages.Entry::inputId)
                    .collect(java.util.stream.Collectors.toSet()))) throw corrupt();
    return value;
  }

  public ImageLedger.Raw raw(ImageRecords.Task task, String path) {
    var entry =
        snapshot(task).entries().stream()
            .filter(e -> e.filePath().equals(path))
            .findFirst()
            .orElseThrow(() -> new TaskFailure(409, "FS_NOT_FOUND"));
    var row = input(task, entry.inputId());
    byte[] bytes = records.decode(row.base64());
    if (bytes.length != entry.size()
        || !records.hash(bytes).equals(entry.sha256())
        || !entry.sha256().equals(row.rawSha256())
        || entry.size() != row.rawSize()
        || !entry.filePath().equals(row.path())) throw corrupt();
    return new ImageLedger.Raw(entry, bytes);
  }

  private ImageRecords.Input input(ImageRecords.Task task, String inputId) {
    var found = records.selectImageInput(task.tenantId(), task.id(), inputId);
    if (found.size() != 1
        || found.getFirst().valid() != 1
        || found.getFirst().revokedAt() != null
        || found.getFirst().rawBase64() == null) throw new TaskFailure(409, "IMAGE_UNAVAILABLE");
    return found.getFirst();
  }

  public void reserve(ImageRecords.Task task, String invocation, String image, String inputId) {
    snapshot(task);
    input(task, inputId);
    var inv = records.selectRunningImageInvocation(task.tenantId(), task.id(), invocation);
    if (inv.size() != 1) throw new TaskFailure(409, "INVOCATION_STATE");
    String path = records.filePath(inv.getFirst().inputSnapshot());
    if (!input(task, inputId).logicalPath().equals(path)) throw corrupt();
    if (!records.selectProducerImages(task.tenantId(), invocation).isEmpty())
      throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
    records.reserveImageObject(
        task.tenantId(), task.id(), image, invocation, inputId, "jimu-image-v1");
  }

  public void succeeded(
      ImageRecords.Task task, String invocation, String key, ImagePort.Result result) {
    var raw = raw(task, result.filePath());
    var image = result.image();
    if (!raw.entry().inputId().equals(result.inputId())
        || !raw.entry().sha256().equals(result.sourceSha256())
        || raw.entry().size() != result.sourceSize()
        || !result.sourceRef().equals("image:" + invocation)
        || !key.equals(image.imageId() + "-" + image.sha256())
        || !image.normalizerVersion().equals("jimu-image-v1")
        || !image.mediaType().equals("image/png")) throw corrupt();
    if (records.commitImageObject(
            key,
            image.sha256(),
            image.size(),
            image.width(),
            image.height(),
            image.originalWidth(),
            image.originalHeight(),
            task.tenantId(),
            task.id(),
            image.imageId(),
            invocation,
            result.inputId())
        != 1) throw corrupt();
  }

  public ImageLedger.ObjectValue object(ImageRecords.Task task, ImagePort.Result ref) {
    var entry =
        snapshot(task).entries().stream()
            .filter(e -> e.inputId().equals(ref.inputId()))
            .findFirst()
            .orElseThrow(ImagePersistenceServiceImpl::corrupt);
    var input = input(task, entry.inputId());
    var rows = records.selectRetainedImageObject(task.tenantId(), task.id(), ref.image().imageId());
    if (rows.size() != 1) throw new TaskFailure(409, "IMAGE_UNAVAILABLE");
    var row = rows.getFirst();
    String invocation = row.producerInvocation();
    var invs = records.selectSucceededImageInvocation(task.tenantId(), task.id(), invocation);
    if (invs.size() != 1) throw corrupt();
    var inv = invs.getFirst();
    String output = inv.outputSnapshot(), args = inv.inputSnapshot();
    var pin = records.readTools(task.toolsSnapshot()).pins().get("read_image");
    if (pin == null
        || !pin.version().equals(inv.pluginVersion())
        || !pin.digest().equals(inv.pluginDigest())
        || !records.hash(output).equals(inv.outputDigest())
        || !records.hash(args).equals(inv.inputDigest())
        || !records.readResult(output).equals(ref)
        || !records.filePath(args).equals(ref.filePath())) throw corrupt();
    var image =
        new ImagePort.Image(
            row.imageId(),
            "image/png",
            row.sha256(),
            row.size(),
            (int) row.width(),
            (int) row.height(),
            (int) row.originalWidth(),
            (int) row.originalHeight(),
            row.normalizerVersion());
    var expected =
        new ImagePort.Result(
            input.path(),
            input.inputId(),
            "image:" + invocation,
            input.rawSha256(),
            input.rawSize(),
            image);
    if (!expected.equals(ref)
        || !entry.filePath().equals(ref.filePath())
        || !entry.sha256().equals(ref.sourceSha256())
        || entry.size() != ref.sourceSize()) throw corrupt();
    return new ImageLedger.ObjectValue(ref, row.snapshotKey());
  }

  public List<ImageLedger.ObjectValue> step(ImageRecords.Task task, String step) {
    return records.selectStepImageOutputs(task.tenantId(), task.id(), step).stream()
        .map(r -> object(task, records.readResult(r.outputSnapshot())))
        .toList();
  }
}
