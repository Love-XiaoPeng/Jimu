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

  Fact fact(String tenant, String id);

  Stored load(String tenant, String id);

  void insert(String tenant, String id, Stored stored);

  void body(String tenant, String id, long bytes);

  String hash(String raw);

  int bytes(String raw);

  String genesis(Genesis value);

  Genesis genesis(String raw);

  String graph(Graph value);

  Graph graph(String raw);

  String output(Result value);

  void checkpoint(long operations);
}
