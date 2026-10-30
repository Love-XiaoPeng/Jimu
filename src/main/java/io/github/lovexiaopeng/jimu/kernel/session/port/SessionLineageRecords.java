package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Graph;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Result;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Stored;
import java.util.List;

/** 原UoW内的显式完整键读取、正文计量及唯一插入；不另开事务。 */
public interface SessionLineageRecords {
  record Fact(
      String tenant,
      String id,
      String created,
      String request,
      String requestDigest,
      String authorityDigest,
      String bindingDigest,
      List<String> sources,
      String declaredParent) {
    public Fact {
      sources = List.copyOf(sources);
    }
  }

  default io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
      verified(String tenant, String caller) {
    return null;
  }

  default void verified(
      String tenant,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
          proof) {}

  default <T> T withProof(java.util.function.Supplier<T> work) {
    return work.get();
  }

  default void scratch(long bytes) {}

  default boolean requestVerified(Fact fact) {
    return false;
  }

  default void created(
      String tenant,
      String caller,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
          proof) {
    verified(tenant, caller, proof);
  }

  /** 仅create已验证生成值可登记；普通load不能建立创建witness。 */
  default boolean createdReadback(
      String tenant,
      String caller,
      Stored generated,
      io.github.lovexiaopeng.jimu.kernel.session.service.SessionLineageService.VerifiedGraph
          proof) {
    return false;
  }

  Fact fact(String tenant, String id);

  Stored load(String tenant, String id);

  void insert(String tenant, String id, Stored stored);

  void body(String tenant, String id, long bytes);

  String hash(String raw);

  int bytes(String raw);

  String genesis(Genesis value);

  Genesis genesis(String raw);

  String graph(Graph value);

  /** Legacy full-payload decoder. Reference codecs need fresh sources via matchGraph instead. */
  Graph graph(String raw);

  /** 每源原字节先独立核摘要；与本次共享且已核的有序Node逐字段匹配。 */
  default boolean matchGraph(Stored stored, Graph expected) {
    var actual = graph(stored.graph());
    checkpoint(actual.nodes().size());
    return actual.equals(expected);
  }

  String output(Result value);

  void checkpoint(long operations);
}
