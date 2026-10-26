package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationMaterials;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.EvaluationPlans;
import io.github.lovexiaopeng.jimu.kernel.model.api.EvaluationPlan;
import io.github.lovexiaopeng.jimu.kernel.model.port.MaterialEvidenceRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ToolSnapshot;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;

/** 同DS/UoW单项Mapper和原codec适配；原行及JSON/Checkpoint仅惰性包装。 */
final class MapperMaterialEvidenceRecords implements MaterialEvidenceRecords {
  private final JdbcLedger ledger;

  MapperMaterialEvidenceRecords(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  static Row wrap(Map<String, Object> row) {
    return new RowView(row);
  }

  static Map<String, Object> unwrap(Row row) {
    return ((RowView) row).row();
  }

  static EvaluationMaterials.Material unwrap(Material value) {
    return value == null ? null : ((MaterialView) value).value();
  }

  private record RowView(Map<String, Object> row) implements Row {
    public String materialSnapshot() {
      return (String) row.get("material_snapshot");
    }

    public String materialDigest() {
      return (String) row.get("material_digest");
    }

    public String evaluationSnapshot() {
      return (String) row.get("evaluation_snapshot");
    }

    public String goal() {
      return (String) row.get("goal");
    }

    public java.time.Instant reportTime() {
      return JdbcLedger.time(row, "report_time");
    }

    public String materialSystemDigest() {
      return (String) row.get("material_system_digest");
    }

    public String tenantId() {
      return (String) row.get("tenant_id");
    }

    public String id() {
      return (String) row.get("id");
    }

    public String outputSnapshot() {
      return (String) row.get("output_snapshot");
    }

    public String outputDigest() {
      return (String) row.get("output_digest");
    }

    public String inputSnapshot() {
      return (String) row.get("input_snapshot");
    }

    public String tool() {
      return (String) row.get("tool");
    }

    public String materialFileSnapshot() {
      return (String) row.get("material_file_snapshot");
    }

    public String materialFileDigest() {
      return (String) row.get("material_file_digest");
    }

    public long recordVersion() {
      return ((Number) row.get("record_version")).longValue();
    }

    public String requestSnapshot() {
      return (String) row.get("request_snapshot");
    }

    public String requestDigest() {
      return (String) row.get("request_digest");
    }

    public String responseSnapshot() {
      return (String) row.get("response_snapshot");
    }

    public long ordinal() {
      return ((Number) row.get("ordinal")).longValue();
    }

    public String responseDigest() {
      return (String) row.get("response_digest");
    }

    public String slot() {
      return (String) row.get("slot");
    }

    public String providerId() {
      return (String) row.get("provider_id");
    }

    public String inputDigest() {
      return (String) row.get("input_digest");
    }

    public String state() {
      return (String) row.get("state");
    }

    public String stepId() {
      return (String) row.get("step_id");
    }

    public String dispatchId() {
      return (String) row.get("dispatch_id");
    }

    public String modelDigest() {
      return (String) row.get("model_digest");
    }

    public Object evaluationSnapshotObject() {
      return row.get("evaluation_snapshot");
    }

    public Object materialFileSnapshotObject() {
      return row.get("material_file_snapshot");
    }

    public Object responseSnapshotObject() {
      return row.get("response_snapshot");
    }

    public Object responseDigestObject() {
      return row.get("response_digest");
    }

    public Object dispatchIdObject() {
      return row.get("dispatch_id");
    }

    public Object requestDigestObject() {
      return row.get("request_digest");
    }
  }

  private static List<Row> lazy(List<Map<String, Object>> rows) {
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public Row get(int i) {
        return wrap(rows.get(i));
      }
    };
  }

  private record MaterialView(EvaluationMaterials.Material value) implements Material {
    public String goal() {
      return value.goal();
    }

    public String systemTemplate() {
      return value.systemTemplate();
    }

    public String target() {
      return value.target();
    }

    public String initialContent() {
      return value.initialContent();
    }
  }

  private static Material materialView(EvaluationMaterials.Material value) {
    return value == null ? null : new MaterialView(value);
  }

  private record JsonView(com.fasterxml.jackson.databind.JsonNode node) implements Json {
    private static Json view(com.fasterxml.jackson.databind.JsonNode node) {
      return node == null ? null : new JsonView(node);
    }

    public Json path(String field) {
      return view(node.path(field));
    }

    public Json path(int index) {
      return view(node.path(index));
    }

    public Json get(int index) {
      return view(node.get(index));
    }

    public String asText() {
      return node.asText();
    }

    public String asText(String fallback) {
      return node.asText(fallback);
    }

    public int asInt() {
      return node.asInt();
    }

    public long asLong() {
      return node.asLong();
    }

    public boolean isNull() {
      return node.isNull();
    }

    public boolean isArray() {
      return node.isArray();
    }

    public int size() {
      return node.size();
    }

    public Json addObject() {
      return view(((com.fasterxml.jackson.databind.node.ArrayNode) node).addObject());
    }

    public Json put(String field, String value) {
      return view(((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field, value));
    }

    public Json withArray(String field) {
      return view(node.withArray(field));
    }

    public Json putObject(String field) {
      return view(((com.fasterxml.jackson.databind.node.ObjectNode) node).putObject(field));
    }

    public Json add(Json value) {
      return view(
          ((com.fasterxml.jackson.databind.node.ArrayNode) node)
              .add(value == null ? null : ((JsonView) value).node()));
    }

    public java.util.Iterator<Json> iterator() {
      var it = node.iterator();
      return new java.util.Iterator<>() {
        public boolean hasNext() {
          return it.hasNext();
        }

        public Json next() {
          return view(it.next());
        }
      };
    }

    @Override
    public boolean equals(Object other) {
      return node.equals(other instanceof JsonView value ? value.node() : other);
    }

    @Override
    public int hashCode() {
      return node.hashCode();
    }
  }

  private record MessageView(
      io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage value)
      implements Message {
    public List<ImagePort.Result> images() {
      return value.images();
    }
  }

  private static final class Messages extends AbstractList<Message> {
    private final List<io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage>
        values;

    Messages(
        List<io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage> values) {
      this.values = values;
    }

    public int size() {
      return values.size();
    }

    public Message get(int index) {
      return new MessageView(values.get(index));
    }
  }

  private record CheckpointView(
      io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint value)
      implements Checkpoint {
    public int version() {
      return value.version();
    }

    public String requestBody() {
      return value.requestBody();
    }

    public String bodyDigest() {
      return value.bodyDigest();
    }

    public String configDigest() {
      return value.configDigest();
    }

    public List<Message> messages() {
      var messages = value.messages();
      return messages == null ? null : new Messages(messages);
    }
  }

  private record DefinitionView(org.springframework.ai.tool.definition.ToolDefinition value)
      implements Definition {}

  /** Pass the original nullable list through; OpenAiProtocol reads model fields before history. */
  private static List<io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.WireMessage>
      unwrapMessages(List<Message> messages) {
    return messages == null ? null : ((Messages) messages).values;
  }

  public <T> T leaseTransaction(Lease lease, java.util.function.Supplier<T> work) {
    return ledger.leaseTx(lease, work);
  }

  public Row fenced(Lease lease, boolean admission) {
    return wrap(ledger.fenced(lease, admission));
  }

  public Material material(String raw) {
    return materialView(Payloads.read(raw, EvaluationMaterials.Material.class));
  }

  public EvaluationPlan.Binding binding(String raw) {
    return Payloads.read(raw, EvaluationPlan.Binding.class);
  }

  public EvaluationPlan.Plan plan(String id) {
    return EvaluationPlans.plan(id);
  }

  public Material load(EvaluationPlan.Plan plan, String sample) {
    return materialView(EvaluationMaterials.load(plan, sample));
  }

  public String hash(String value) {
    return Payloads.hash(value);
  }

  public String json(Object value) {
    return Payloads.json(value);
  }

  public ReportFile file(String raw) {
    return Payloads.read(raw, ReportFile.class);
  }

  public Json readJson(String raw) {
    return new JsonView(Payloads.read(raw, com.fasterxml.jackson.databind.JsonNode.class));
  }

  public Json readArray(String raw) {
    return new JsonView(Payloads.read(raw, com.fasterxml.jackson.databind.node.ArrayNode.class));
  }

  public void source(Material material, String url, String output) {
    EvaluationMaterials.source(unwrap(material), url, output);
  }

  public void body(Material material, String request, String time) {
    EvaluationMaterials.body(unwrap(material), request, time);
  }

  public List<Row> materialSources(String tenant, String task) {
    return lazy(
        ledger.mappers.invocations().selectMaterialSources(ledger.queryContext(), tenant, task));
  }

  public List<Row> materialFileHistory(String tenant, String task) {
    return lazy(
        ledger
            .mappers
            .invocations()
            .selectMaterialFileHistory(ledger.queryContext(), tenant, task));
  }

  public boolean businessTask(Row task) {
    return ledger.businessTask(unwrap(task));
  }

  public List<String> caps(Row task) {
    return ledger.caps(unwrap(task));
  }

  public ToolSnapshot tools(Row task) {
    return ledger.tools(unwrap(task));
  }

  public ModelSnapshot snapshot(Row task) {
    return ledger.snapshot(unwrap(task));
  }

  public String modelDigest(ModelSnapshot snapshot) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelProfiles.digest(snapshot);
  }

  public ReportInstructions reportInstructions() {
    var reports = new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportPolicy();
    return new ReportInstructions() {
      public String instruction(ReportContext context) {
        return reports.instruction(context);
      }

      public String currentInstruction(ReportContext context) {
        return reports.currentInstruction(context);
      }
    };
  }

  public String imageInstruction(ReportContext context) {
    return new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ImageReportComposer()
        .instruction(context);
  }

  public String compositionInstruction(ReportContext context) {
    return new io.github.lovexiaopeng.jimu.adapters.runtime.contract.ReportComposer()
        .instruction(context);
  }

  public TaskImages.Snapshot imageSnapshot(Row task) {
    return ledger.imageService.snapshot(ledger.imageRecords.bind(unwrap(task)));
  }

  public List<ImageLedger.ObjectValue> stepImages(Row task, String step) {
    return ledger.imageService.step(ledger.imageRecords.bind(unwrap(task)), step);
  }

  public ImageLedger.ObjectValue imageObject(Row task, ImagePort.Result ref) {
    return ledger.imageService.object(ledger.imageRecords.bind(unwrap(task)), ref);
  }

  public byte[] readImage(String tenant, String key, String hash, long size) {
    return ledger.materialImageBytes(tenant, key, hash, size);
  }

  public String imageDirectory(TaskImages.Snapshot snapshot) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.directory(snapshot);
  }

  public String imageIdentity(ImagePort.Result ref) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.identity(ref);
  }

  public Json imageProvider(ImagePort.Result ref, byte[] bytes) {
    return new JsonView(
        io.github.lovexiaopeng.jimu.adapters.runtime.media.ImageMessages.provider(ref, bytes));
  }

  public Checkpoint checkpoint(String raw) {
    var cp =
        Payloads.read(
            raw, io.github.lovexiaopeng.jimu.adapters.ai.springai.DurableAgent.Checkpoint.class);
    return cp == null ? null : new CheckpointView(cp);
  }

  public byte[] builtinInput() throws java.io.IOException {
    return io.github.lovexiaopeng.jimu.adapters.runtime.contract.BuiltinManifestLoader.resource(
        "input.schema.json");
  }

  public WorkspaceContract workspaceContract(String name, String version)
      throws java.io.IOException {
    var contract =
        new io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts()
            .load(name, version);
    return new WorkspaceContract() {
      public Descriptor descriptor() {
        var d = contract.descriptor();
        return d::outputSha256;
      }

      public String input() {
        return contract.input();
      }
    };
  }

  public String description(String name) {
    return io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts.description(
        name);
  }

  public Definition definition(String name, String description, String schema) {
    return new DefinitionView(
        org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(schema)
            .build());
  }

  public String project(
      ModelSnapshot snapshot,
      List<Message> messages,
      List<Definition> definitions,
      java.util.function.Function<ImagePort.Result, byte[]> images) {
    var values =
        new AbstractList<org.springframework.ai.tool.definition.ToolDefinition>() {
          public int size() {
            return definitions.size();
          }

          public org.springframework.ai.tool.definition.ToolDefinition get(int index) {
            return ((DefinitionView) definitions.get(index)).value();
          }
        };
    return new io.github.lovexiaopeng.jimu.adapters.ai.springai.OpenAiProtocol()
        .body(snapshot, unwrapMessages(messages), values, images);
  }

  public void checkInput(ModelSnapshot snapshot, String projected) {
    ledger.models.checkInput(snapshot, projected);
  }

  public boolean localEvaluation(ModelSnapshot snapshot) {
    return ledger.models.localEvaluation(snapshot);
  }

  public ReportContext reportContext(Row task) {
    return ledger.reportContext(unwrap(task));
  }

  public List<Row> successfulMaterialWrite(String tenant, String task) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper.InvocationMapper
                    .class)
            .successfulMaterialWrite(ledger.queryContext(), tenant, task));
  }

  public List<Row> existingMaterialStep(String tenant, String task) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper.class)
            .existingMaterialStep(ledger.queryContext(), tenant, task));
  }

  public int bindMaterialReceipt(String json, String digest, String tenant, String task) {
    return ledger
        .mappers
        .session()
        .getMapper(io.github.lovexiaopeng.jimu.adapters.persistence.task.mapper.TaskMapper.class)
        .bindMaterialReceipt(ledger.queryContext(), json, digest, tenant, task);
  }

  public List<Row> materialPrecedingSteps(String tenant, String task, int ordinal) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper.class)
            .materialPrecedingSteps(ledger.queryContext(), tenant, task, ordinal));
  }

  public List<Row> materialStepInvocations(String tenant, String task, String step) {
    return lazy(
        ledger
            .mappers
            .session()
            .getMapper(
                io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper.InvocationMapper
                    .class)
            .materialStepInvocations(ledger.queryContext(), tenant, task, step));
  }

  public Row imageComposition(String tenant, String task, String invocation) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper
                        .InvocationMapper.class)
                .imageComposition(ledger.queryContext(), tenant, task, invocation)));
  }

  public Row imageCompositionStep(String tenant, String task, String step) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.execution.mapper.StepMapper
                        .class)
                .imageCompositionStep(ledger.queryContext(), tenant, task, step)));
  }

  public Row imageCompositionDispatch(String tenant, String task, String dispatch) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper
                        .ModelDispatchMapper.class)
                .imageCompositionDispatch(ledger.queryContext(), tenant, task, dispatch)));
  }

  public Row imageSourceDigest(String tenant, String task, String invocation) {
    return wrap(
        JdbcLedger.first(
            ledger
                .mappers
                .session()
                .getMapper(
                    io.github.lovexiaopeng.jimu.adapters.persistence.invocation.mapper
                        .InvocationMapper.class)
                .imageSourceDigest(ledger.queryContext(), tenant, task, invocation)));
  }
}
