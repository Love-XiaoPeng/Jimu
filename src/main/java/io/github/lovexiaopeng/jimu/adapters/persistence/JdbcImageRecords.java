package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageInputs;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Media row integrity inside the calling ledger's fenced transaction. No independent authority. */
final class JdbcImageRecords {
  private final JdbcTemplate db;

  JdbcImageRecords(JdbcTemplate db) {
    this.db = db;
  }

  private static String s(Map<String, Object> row, String key) {
    return (String) row.get(key);
  }

  private static long n(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private static TaskFailure corrupt() {
    return new TaskFailure(409, "SNAPSHOT_CORRUPT");
  }

  void insert(
      Tasks.Identity actor,
      String task,
      List<TaskImages.Input> inputs,
      List<TaskImages.Descriptor> descriptors) {
    var entries = new java.util.ArrayList<TaskImages.Entry>();
    for (int i = 0; i < inputs.size(); i++) {
      var input = inputs.get(i);
      var descriptor = descriptors.get(i);
      String id = UUID.randomUUID().toString();
      db.update(
          "INSERT INTO task_image_input(tenant_id,task_id,input_id,logical_path,raw_sha256,raw_size,raw_base64) VALUES(?,?,?,?,?,?,?)",
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
    String snapshot = Payloads.json(TaskImages.snapshot(entries));
    db.update(
        "UPDATE task SET record_version=3,media_snapshot=?,media_digest=?,media_cleanup_state='RETAINED' WHERE tenant_id=? AND id=?",
        snapshot,
        Payloads.hash(snapshot),
        actor.tenant(),
        task);
  }

  TaskImages.Snapshot snapshot(Map<String, Object> task) {
    String raw = s(task, "media_snapshot");
    if (n(task, "record_version") != 3
        || raw == null
        || !Payloads.hash(raw).equals(task.get("media_digest"))) throw corrupt();
    var value = Payloads.read(raw, TaskImages.Snapshot.class);
    if (!value.equals(TaskImages.snapshot(value.entries()))) throw corrupt();
    value.entries().forEach(e -> input(task, e.inputId()));
    var request = Payloads.read(s(task, "request_snapshot"), Tasks.Request.class);
    var expected =
        value.entries().stream()
            .map(e -> new TaskImages.Descriptor(e.filePath(), e.sha256(), e.size()))
            .toList();
    if (!java.util.Objects.equals(
        expected, request.images() == null ? List.of() : request.images())) throw corrupt();
    var rows =
        db.queryForList(
            "SELECT input_id FROM task_image_input WHERE tenant_id=? AND task_id=?",
            s(task, "tenant_id"),
            s(task, "id"));
    if (rows.size() != value.entries().size()
        || !rows.stream()
            .map(r -> s(r, "input_id"))
            .collect(java.util.stream.Collectors.toSet())
            .equals(
                value.entries().stream()
                    .map(TaskImages.Entry::inputId)
                    .collect(java.util.stream.Collectors.toSet()))) throw corrupt();
    return value;
  }

  ImageLedger.Raw raw(Map<String, Object> task, String path) {
    var entry =
        snapshot(task).entries().stream()
            .filter(e -> e.filePath().equals(path))
            .findFirst()
            .orElseThrow(() -> new TaskFailure(409, "FS_NOT_FOUND"));
    var row = input(task, entry.inputId());
    byte[] bytes = ImageInputs.decode(s(row, "raw_base64"));
    if (bytes.length != entry.size()
        || !Payloads.hash(bytes).equals(entry.sha256())
        || !entry.sha256().equals(s(row, "raw_sha256"))
        || entry.size() != n(row, "raw_size")
        || !entry.filePath().equals(s(row, "logical_path"))) throw corrupt();
    return new ImageLedger.Raw(entry, bytes);
  }

  private Map<String, Object> input(Map<String, Object> task, String inputId) {
    var found =
        db.queryForList(
            "SELECT *,expires_at>CURRENT_TIMESTAMP(6) AS valid FROM task_image_input WHERE tenant_id=? AND task_id=? AND input_id=?",
            s(task, "tenant_id"),
            s(task, "id"),
            inputId);
    if (found.size() != 1
        || n(found.getFirst(), "valid") != 1
        || found.getFirst().get("revoked_at") != null
        || found.getFirst().get("raw_base64") == null)
      throw new TaskFailure(409, "IMAGE_UNAVAILABLE");
    return found.getFirst();
  }

  void reserve(Map<String, Object> task, String invocation, String image, String inputId) {
    snapshot(task);
    input(task, inputId);
    var inv =
        db.queryForList(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND tool='read_image' AND state='RUNNING'",
            s(task, "tenant_id"),
            s(task, "id"),
            invocation);
    if (inv.size() != 1) throw new TaskFailure(409, "INVOCATION_STATE");
    String path =
        Payloads.read(
                s(inv.getFirst(), "input_snapshot"), com.fasterxml.jackson.databind.JsonNode.class)
            .path("file_path")
            .asText();
    if (!input(task, inputId).get("logical_path").equals(path)) throw corrupt();
    if (!db.queryForList(
            "SELECT image_id FROM task_image_object WHERE tenant_id=? AND producer_invocation=?",
            s(task, "tenant_id"),
            invocation)
        .isEmpty()) throw new TaskFailure(409, "EFFECT_OUTCOME_UNKNOWN");
    db.update(
        "INSERT INTO task_image_object(tenant_id,task_id,image_id,producer_invocation,input_id,normalizer_version,phase) VALUES(?,?,?,?,?,?,'RESERVED')",
        s(task, "tenant_id"),
        s(task, "id"),
        image,
        invocation,
        inputId,
        "jimu-image-v1");
  }

  void succeeded(Map<String, Object> task, String invocation, String key, ImagePort.Result result) {
    var raw = raw(task, result.filePath());
    var image = result.image();
    if (!raw.entry().inputId().equals(result.inputId())
        || !raw.entry().sha256().equals(result.sourceSha256())
        || raw.entry().size() != result.sourceSize()
        || !result.sourceRef().equals("image:" + invocation)
        || !key.equals(image.imageId() + "-" + image.sha256())
        || !image.normalizerVersion().equals("jimu-image-v1")
        || !image.mediaType().equals("image/png")) throw corrupt();
    if (db.update(
            "UPDATE task_image_object SET snapshot_key=?,sha256=?,size=?,width=?,height=?,original_width=?,original_height=?,phase='COMMITTED' WHERE tenant_id=? AND task_id=? AND image_id=? AND producer_invocation=? AND input_id=? AND phase='RESERVED'",
            key,
            image.sha256(),
            image.size(),
            image.width(),
            image.height(),
            image.originalWidth(),
            image.originalHeight(),
            s(task, "tenant_id"),
            s(task, "id"),
            image.imageId(),
            invocation,
            result.inputId())
        != 1) throw corrupt();
  }

  ImageLedger.ObjectValue object(Map<String, Object> task, ImagePort.Result ref) {
    var entry =
        snapshot(task).entries().stream()
            .filter(e -> e.inputId().equals(ref.inputId()))
            .findFirst()
            .orElseThrow(JdbcImageRecords::corrupt);
    var input = input(task, entry.inputId());
    var rows =
        db.queryForList(
            "SELECT * FROM task_image_object WHERE tenant_id=? AND task_id=? AND image_id=? AND phase='COMMITTED' AND cleanup_state='RETAINED' AND expires_at>CURRENT_TIMESTAMP(6)",
            s(task, "tenant_id"),
            s(task, "id"),
            ref.image().imageId());
    if (rows.size() != 1) throw new TaskFailure(409, "IMAGE_UNAVAILABLE");
    var row = rows.getFirst();
    String invocation = s(row, "producer_invocation");
    var invs =
        db.queryForList(
            "SELECT * FROM invocation WHERE tenant_id=? AND task_id=? AND id=? AND tool='read_image' AND state='SUCCEEDED'",
            s(task, "tenant_id"),
            s(task, "id"),
            invocation);
    if (invs.size() != 1) throw corrupt();
    var inv = invs.getFirst();
    String output = s(inv, "output_snapshot"), args = s(inv, "input_snapshot");
    var pin =
        Payloads.read(s(task, "tools_snapshot"), ExecutionLedger.ToolSnapshot.class)
            .pins()
            .get("read_image");
    if (pin == null
        || !pin.version().equals(inv.get("plugin_version"))
        || !pin.digest().equals(inv.get("plugin_digest"))
        || !Payloads.hash(output).equals(inv.get("output_digest"))
        || !Payloads.hash(args).equals(inv.get("input_digest"))
        || !Payloads.read(output, ImagePort.Result.class).equals(ref)
        || !Payloads.read(args, com.fasterxml.jackson.databind.JsonNode.class)
            .path("file_path")
            .asText()
            .equals(ref.filePath())) throw corrupt();
    var image =
        new ImagePort.Image(
            s(row, "image_id"),
            "image/png",
            s(row, "sha256"),
            n(row, "size"),
            (int) n(row, "width"),
            (int) n(row, "height"),
            (int) n(row, "original_width"),
            (int) n(row, "original_height"),
            s(row, "normalizer_version"));
    var expected =
        new ImagePort.Result(
            s(input, "logical_path"),
            s(input, "input_id"),
            "image:" + invocation,
            s(input, "raw_sha256"),
            n(input, "raw_size"),
            image);
    if (!expected.equals(ref)
        || !entry.filePath().equals(ref.filePath())
        || !entry.sha256().equals(ref.sourceSha256())
        || entry.size() != ref.sourceSize()) throw corrupt();
    return new ImageLedger.ObjectValue(ref, s(row, "snapshot_key"));
  }

  List<ImageLedger.ObjectValue> step(Map<String, Object> task, String step) {
    return db
        .queryForList(
            "SELECT output_snapshot FROM invocation WHERE tenant_id=? AND task_id=? AND step_id=? AND tool='read_image' AND state='SUCCEEDED' ORDER BY CAST(SUBSTRING_INDEX(slot,':',1) AS UNSIGNED)",
            s(task, "tenant_id"),
            s(task, "id"),
            step)
        .stream()
        .map(r -> object(task, Payloads.read(s(r, "output_snapshot"), ImagePort.Result.class)))
        .toList();
  }
}
