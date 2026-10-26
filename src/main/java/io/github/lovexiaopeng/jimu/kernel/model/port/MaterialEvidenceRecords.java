package io.github.lovexiaopeng.jimu.kernel.model.port;

import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;

/** 惰性字段/codec视图与单项I/O，构造不读取原行或框架对象。 */
public interface MaterialEvidenceRecords {
  interface Row {
    String materialSnapshot();

    String materialDigest();

    String evaluationSnapshot();

    String goal();

    java.time.Instant reportTime();

    String materialSystemDigest();

    String tenantId();

    String id();

    String outputSnapshot();

    String outputDigest();

    String inputSnapshot();

    String tool();

    String materialFileSnapshot();

    String materialFileDigest();

    long recordVersion();

    String requestSnapshot();

    String requestDigest();

    String responseSnapshot();

    long ordinal();

    String responseDigest();

    String slot();

    String providerId();

    String inputDigest();

    String state();

    String stepId();

    String dispatchId();

    String modelDigest();

    Object evaluationSnapshotObject();

    Object materialFileSnapshotObject();

    Object responseSnapshotObject();

    Object responseDigestObject();

    Object dispatchIdObject();

    Object requestDigestObject();
  }

  interface Material {
    String goal();

    String systemTemplate();

    String target();

    String initialContent();
  }

  interface Json extends Iterable<Json> {
    Json path(String field);

    Json path(int index);

    Json get(int index);

    String asText();

    String asText(String fallback);

    int asInt();

    long asLong();

    boolean isNull();

    boolean isArray();

    int size();

    Json addObject();

    Json put(String field, String value);

    Json withArray(String field);

    Json putObject(String field);

    Json add(Json value);
  }

  interface ReportInstructions {
    String instruction(ReportContext context);

    String currentInstruction(ReportContext context);
  }

  interface Message {
    List<ImagePort.Result> images();
  }

  interface Checkpoint {
    int version();

    String requestBody();

    String bodyDigest();

    String configDigest();

    List<Message> messages();
  }

  interface Descriptor {
    String outputSha256();
  }

  interface WorkspaceContract {
    Descriptor descriptor();

    String input();
  }

  interface Definition {}

  <T> T leaseTransaction(Lease lease, java.util.function.Supplier<T> work);

  Row fenced(Lease lease, boolean admission);

  Material material(String raw);

  EvaluationPlan.Binding binding(String raw);

  EvaluationPlan.Plan plan(String id);

  Material load(EvaluationPlan.Plan plan, String sample);

  String hash(String value);

  String json(Object value);

  ReportFile file(String raw);

  Json readJson(String raw);

  Json readArray(String raw);

  void source(Material material, String url, String output);

  void body(Material material, String request, String time);

  List<Row> materialSources(String tenant, String task);

  List<Row> materialFileHistory(String tenant, String task);

  boolean businessTask(Row task);

  List<String> caps(Row task);

  ToolSnapshot tools(Row task);

  ModelSnapshot snapshot(Row task);

  String modelDigest(ModelSnapshot snapshot);

  ReportInstructions reportInstructions();

  String imageInstruction(ReportContext context);

  String compositionInstruction(ReportContext context);

  TaskImages.Snapshot imageSnapshot(Row task);

  List<ImageLedger.ObjectValue> stepImages(Row task, String step);

  ImageLedger.ObjectValue imageObject(Row task, ImagePort.Result ref);

  byte[] readImage(String tenant, String key, String hash, long size);

  String imageDirectory(TaskImages.Snapshot snapshot);

  String imageIdentity(ImagePort.Result ref);

  Json imageProvider(ImagePort.Result ref, byte[] bytes);

  Checkpoint checkpoint(String raw);

  byte[] builtinInput() throws java.io.IOException;

  WorkspaceContract workspaceContract(String name, String version) throws java.io.IOException;

  String description(String name);

  Definition definition(String name, String description, String schema);

  String project(
      ModelSnapshot snapshot,
      List<Message> messages,
      List<Definition> definitions,
      java.util.function.Function<ImagePort.Result, byte[]> images);

  void checkInput(ModelSnapshot snapshot, String projected);

  boolean localEvaluation(ModelSnapshot snapshot);

  ReportContext reportContext(Row task);

  List<Row> successfulMaterialWrite(String tenant, String task);

  List<Row> existingMaterialStep(String tenant, String task);

  int bindMaterialReceipt(String json, String digest, String tenant, String task);

  List<Row> materialPrecedingSteps(String tenant, String task, int ordinal);

  List<Row> materialStepInvocations(String tenant, String task, String step);

  Row imageComposition(String tenant, String task, String invocation);

  Row imageCompositionStep(String tenant, String task, String step);

  Row imageCompositionDispatch(String tenant, String task, String dispatch);

  Row imageSourceDigest(String tenant, String task, String invocation);
}
