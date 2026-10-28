package io.github.lovexiaopeng.jimu.kernel.task.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.List;

/** 受理所需的类型化事实和具名写入；没有 SQL、可变行 Map 或隐式查询上下文。 */
public interface TaskAdmissionRecords {
  record Existing(String snapshot, String digest) {}

  record Room(
      long queued,
      long queueLimit,
      long historyRoom,
      long directoryRoom,
      long todoRoom,
      long questionHeld,
      long questionLimit,
      long storageRoom) {}

  record Policy(int version, String snapshot) {}

  record EvaluationBinding(String snapshot, String sample) {}

  record Material(String goal, String snapshot, String systemTemplate) {}

  List<TaskImages.Descriptor> describe(List<TaskImages.Input> input);

  String json(Object value);

  String hash(String value);

  default boolean sameJson(String left, String right) {
    return left.equals(right);
  }

  Tasks.Request readRequest(String snapshot);

  boolean questionsEnabled();

  boolean mcpEnabled();

  Admission lock(Tasks.Identity identity, String key, List<String> historySources, String callerId);

  interface Admission {
    Existing existing(boolean ask);

    Tasks.View replay(boolean recheckAccess);

    Room room();

    void requireImageModel();

    boolean imageStoreAvailable();

    long installationEpoch(List<String> capabilities);

    McpLedger.Binding bindMcp();

    boolean searchAvailable();

    PluginPin pin(String capability);

    ModelSnapshot model();

    String requireScope(ModelSnapshot model);

    Policy policy();

    boolean hasEvaluation();

    long evaluationCost();

    boolean localModel(ModelSnapshot model);

    boolean hasScopeRequirement();

    boolean sessionReadEnabled();

    boolean priorSearchEnabled();

    default boolean lineageEnabled() {
      return false;
    }

    boolean sessionTraceEnabled();

    boolean sessionSearchEnabled();

    boolean directoryEnabled();

    boolean todoEnabled();

    void requireSessionModel(ModelSnapshot model, String kind);

    void requireDirectoryModel(ModelSnapshot model);

    void requireTodoModel(ModelSnapshot model);

    PluginPin contract(String capability, boolean prior, boolean trace, boolean search);

    default PluginPin lineageContract(String capability) {
      throw new UnsupportedOperationException("v5 contract required");
    }

    String questionBinding(String version);

    String questionDigest(String version);

    void admitModel(ModelSnapshot model);

    EvaluationBinding bindEvaluation(ModelSnapshot model);

    Material material(EvaluationBinding binding);

    boolean sampleAlreadyBound(ModelSnapshot model, EvaluationBinding binding);

    String insert(
        String callerId,
        String key,
        Tasks.Request request,
        String snapshot,
        String digest,
        long epoch);

    void bindSnapshots(String task, ModelSnapshot model, long cost, String tools, String scope);

    void createSession(String task, List<String> historySources);

    default void createLineage(String task, String parent) {
      throw new UnsupportedOperationException("v5 lineage required");
    }

    void createDirectory(String task);

    void createTodo(String task);

    void bindQuestion(String task, String binding);

    void bindMcp(String task, McpLedger.Binding binding);

    void bindPolicy(String task, Policy policy, long tokens);

    void insertImages(
        String task,
        List<TaskImages.Input> input,
        List<TaskImages.Descriptor> descriptors,
        long held);

    void bindEvaluation(
        String task,
        Tasks.Request request,
        ModelSnapshot model,
        EvaluationBinding binding,
        Material material);

    void reserve(String task, String suffix, String kind, long amount);

    Tasks.View accepted(String task);
  }
}
