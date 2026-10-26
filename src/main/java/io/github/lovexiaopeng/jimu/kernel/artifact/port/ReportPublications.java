package io.github.lovexiaopeng.jimu.kernel.artifact.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import java.util.List;
import java.util.function.Supplier;

/** 报告提交的惰性事实、原编解码和单项持久操作；不在适配器判定用例资格。 */
public interface ReportPublications {
  interface Task {
    boolean publicReport();

    PluginPin compositionPin();

    List<Composition> confirmed();

    boolean imageMatches(String input, String output, ReportFile file, Composition composition);

    boolean textMatches(String input, String output, ReportFile file);

    void event(String fact, String type);
  }

  interface Invocation {
    String tool();

    String state();
  }

  interface Composition {
    String input();

    String output();

    String inputDigest();

    String outputDigest();

    String version();

    String digest();
  }

  interface Artifact {
    String id();

    String uploadId();

    String state();

    String hash();

    String snapshotKey();

    String receipt();

    String receiptDigest();
  }

  /** 原 JSON 节点的单次操作视图，保留 missing/null、对象迭代和宽松 asText/asLong。 */
  interface Json extends Iterable<Json> {
    Json path(String name);

    Json get(int index);

    String text();

    long number();

    boolean array();

    int size();
  }

  interface Budget {
    long held();

    long limit();
  }

  <T> T transaction(Lease lease, Supplier<T> work);

  Task fenced(Lease lease, boolean admission);

  Invocation invocation(Lease lease, String invocation);

  String input(Lease lease, String invocation);

  Json read(String json);

  ReportFile receipt(String json);

  String json(ReportFile file);

  String hash(String text);

  List<Artifact> matching(Lease lease, String invocation, String digest);

  List<Artifact> otherProducer(Lease lease, String invocation);

  Budget bucket(Lease lease);

  String newId();

  void insert(
      Lease lease,
      String artifact,
      String invocation,
      String upload,
      ReportFile file,
      String receipt,
      String digest);

  void hold(Lease lease, long size);

  void reserve(Lease lease, String artifact, long size);

  List<Artifact> stagedIds(Lease lease, String invocation);

  List<Artifact> artifacts(Lease lease, String invocation);

  void publish(Lease lease, String key, String artifact);

  void settleStorage(Lease lease, String artifact);

  void succeed(Lease lease, String output, String digest, String invocation);

  void settleTool(Lease lease, String invocation);
}
