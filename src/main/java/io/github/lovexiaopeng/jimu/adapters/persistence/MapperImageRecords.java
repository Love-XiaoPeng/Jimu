package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.media.port.ImageRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

final class MapperImageRecords implements ImageRecords {
  private final JdbcLedger ledger;

  MapperImageRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  Task bind(Map<String, Object> row) {
    return new TaskRow(row);
  }

  private record TaskRow(Map<String, Object> row) implements Task {
    public String tenantId() {
      return (String) row.get("tenant_id");
    }

    public String id() {
      return (String) row.get("id");
    }

    public String mediaSnapshot() {
      return (String) row.get("media_snapshot");
    }

    public long recordVersion() {
      return ((Number) row.get("record_version")).longValue();
    }

    public Object mediaDigest() {
      return row.get("media_digest");
    }

    public String requestSnapshot() {
      return (String) row.get("request_snapshot");
    }

    public String toolsSnapshot() {
      return (String) row.get("tools_snapshot");
    }
  }

  private record InputRow(Map<String, Object> row) implements Input {
    public long valid() {
      return ((Number) row.get("valid")).longValue();
    }

    public Object revokedAt() {
      return row.get("revoked_at");
    }

    public Object rawBase64() {
      return row.get("raw_base64");
    }

    public Object logicalPath() {
      return row.get("logical_path");
    }

    public String rawSha256() {
      return (String) row.get("raw_sha256");
    }

    public long rawSize() {
      return ((Number) row.get("raw_size")).longValue();
    }

    public String inputId() {
      return (String) row.get("input_id");
    }

    public String base64() {
      return (String) row.get("raw_base64");
    }

    public String path() {
      return (String) row.get("logical_path");
    }
  }

  private record InvocationRow(Map<String, Object> row) implements Invocation {
    public String inputSnapshot() {
      return (String) row.get("input_snapshot");
    }

    public String outputSnapshot() {
      return (String) row.get("output_snapshot");
    }

    public Object pluginVersion() {
      return row.get("plugin_version");
    }

    public Object pluginDigest() {
      return row.get("plugin_digest");
    }

    public Object outputDigest() {
      return row.get("output_digest");
    }

    public Object inputDigest() {
      return row.get("input_digest");
    }
  }

  private record ImageRow(Map<String, Object> row) implements Image {
    public String imageId() {
      return (String) row.get("image_id");
    }

    public String producerInvocation() {
      return (String) row.get("producer_invocation");
    }

    public String sha256() {
      return (String) row.get("sha256");
    }

    public long size() {
      return ((Number) row.get("size")).longValue();
    }

    public long width() {
      return ((Number) row.get("width")).longValue();
    }

    public long height() {
      return ((Number) row.get("height")).longValue();
    }

    public long originalWidth() {
      return ((Number) row.get("original_width")).longValue();
    }

    public long originalHeight() {
      return ((Number) row.get("original_height")).longValue();
    }

    public String normalizerVersion() {
      return (String) row.get("normalizer_version");
    }

    public String snapshotKey() {
      return (String) row.get("snapshot_key");
    }
  }

  private record InputIdRow(Map<String, Object> row) implements InputId {
    public String inputId() {
      return (String) row.get("input_id");
    }
  }

  private record OutputRow(Map<String, Object> row) implements Output {
    public String outputSnapshot() {
      return (String) row.get("output_snapshot");
    }
  }

  private record BudgetRow(Map<String, Object> row) implements Budget {
    public long storageLimit() {
      return ((Number) row.get("storage_limit")).longValue();
    }

    public long storageHeld() {
      return ((Number) row.get("storage_held")).longValue();
    }

    public long mediaCheckpointHeld() {
      return ((Number) row.get("media_checkpoint_held")).longValue();
    }
  }

  private static <T> List<T> lazy(
      List<Map<String, Object>> rows, Function<Map<String, Object>, T> wrap) {
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public T get(int i) {
        return wrap.apply(rows.get(i));
      }
    };
  }

  public <T> T leaseTransaction(Lease lease, Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public Task fenced(Lease lease, boolean admission) {
    return bind(ledger.fenced(lease, admission));
  }

  public Budget budget(String tenant) {
    return new BudgetRow(ledger.bucket(tenant));
  }

  public void requireImages(Task task) {
    io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelImages.require(
        ledger.snapshot(((TaskRow) task).row()));
  }

  public void event(Task task, String fact, String type, String code) {
    ledger.event(((TaskRow) task).row(), fact, type, code);
  }

  public void reserveStorage(
      String tenant,
      String task,
      String image,
      String dispatch,
      String category,
      long amount,
      String state,
      String outcome) {
    ledger.reserve(tenant, task, image, dispatch, category, amount, state, outcome);
  }

  public void toolSucceeded(Lease lease, String invocation, String output) {
    ledger.toolSucceeded(lease, invocation, output);
  }

  public String id() {
    return JdbcLedger.id();
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public String hash(byte[] value) {
    return Payloads.hash(value);
  }

  public TaskImages.Snapshot readSnapshot(String raw) {
    return Payloads.read(raw, TaskImages.Snapshot.class);
  }

  public Tasks.Request readRequest(String raw) {
    return Payloads.read(raw, Tasks.Request.class);
  }

  public ExecutionLedger.ToolSnapshot readTools(String raw) {
    return Payloads.read(raw, ExecutionLedger.ToolSnapshot.class);
  }

  public ImagePort.Result readResult(String raw) {
    return Payloads.read(raw, ImagePort.Result.class);
  }

  public String filePath(String raw) {
    return Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class)
        .path("file_path")
        .asText();
  }

  public byte[] decode(String raw) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageInputs.decode(raw);
  }

  public int insertInput(
      String tenant,
      String task,
      String inputId,
      String logicalPath,
      String sha256,
      long size,
      String rawBase64) {
    return ledger
        .mappers
        .imageInputs()
        .insertInput(
            ledger.queryContext(), tenant, task, inputId, logicalPath, sha256, size, rawBase64);
  }

  public int saveMediaSnapshot(String snapshot, String digest, String tenant, String task) {
    return ledger
        .mappers
        .tasks()
        .saveMediaSnapshot(ledger.queryContext(), snapshot, digest, tenant, task);
  }

  public List<InputId> selectInputIds(String tenant, String task) {
    return lazy(
        ledger.mappers.imageInputs().selectInputIds(ledger.queryContext(), tenant, task),
        InputIdRow::new);
  }

  public List<Input> selectImageInput(String tenant, String task, String inputId) {
    return lazy(
        ledger.mappers.imageInputs().selectImageInput(ledger.queryContext(), tenant, task, inputId),
        InputRow::new);
  }

  public List<Invocation> selectRunningImageInvocation(
      String tenant, String task, String invocation) {
    return lazy(
        ledger
            .mappers
            .invocations()
            .selectRunningImageInvocation(ledger.queryContext(), tenant, task, invocation),
        InvocationRow::new);
  }

  public List<Image> selectProducerImages(String tenant, String invocation) {
    return lazy(
        ledger
            .mappers
            .imageObjects()
            .selectProducerImages(ledger.queryContext(), tenant, invocation),
        ImageRow::new);
  }

  public int reserveImageObject(
      String tenant,
      String task,
      String image,
      String invocation,
      String inputId,
      String normalizer) {
    return ledger
        .mappers
        .imageObjects()
        .reserveImageObject(
            ledger.queryContext(), tenant, task, image, invocation, inputId, normalizer);
  }

  public int commitImageObject(
      String key,
      String sha256,
      long size,
      int width,
      int height,
      int originalWidth,
      int originalHeight,
      String tenant,
      String task,
      String image,
      String invocation,
      String inputId) {
    return ledger
        .mappers
        .imageObjects()
        .commitImageObject(
            ledger.queryContext(),
            key,
            sha256,
            size,
            width,
            height,
            originalWidth,
            originalHeight,
            tenant,
            task,
            image,
            invocation,
            inputId);
  }

  public List<Image> selectRetainedImageObject(String tenant, String task, String image) {
    return lazy(
        ledger
            .mappers
            .imageObjects()
            .selectRetainedImageObject(ledger.queryContext(), tenant, task, image),
        ImageRow::new);
  }

  public List<Invocation> selectSucceededImageInvocation(
      String tenant, String task, String invocation) {
    return lazy(
        ledger
            .mappers
            .invocations()
            .selectSucceededImageInvocation(ledger.queryContext(), tenant, task, invocation),
        InvocationRow::new);
  }

  public List<Output> selectStepImageOutputs(String tenant, String task, String step) {
    return lazy(
        ledger
            .mappers
            .invocations()
            .selectStepImageOutputs(ledger.queryContext(), tenant, task, step),
        OutputRow::new);
  }

  public int holdImageStorage(String tenant) {
    return ledger.mappers.budgets().holdImageStorage(ledger.queryContext(), tenant);
  }

  public int settleImageReservation(long actual, String tenant, String image) {
    return ledger
        .mappers
        .reservations()
        .settleImageReservation(ledger.queryContext(), actual, tenant, image);
  }

  public int releaseImageStorage(long bytes, String tenant) {
    return ledger.mappers.budgets().releaseImageStorage(ledger.queryContext(), bytes, tenant);
  }

  public int cancelImageDecode(String tenant, String task) {
    return ledger.mappers.tasks().cancelImageDecode(ledger.queryContext(), tenant, task);
  }
}
