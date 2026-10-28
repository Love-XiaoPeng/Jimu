package io.github.lovexiaopeng.jimu.kernel.session.domain;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Descendant;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Node;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.PublicNode;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Result;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SessionErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.function.LongConsumer;

/** 最多9节点的纯图规则；全图校验后才返回最近祖先与稳定DFS后代。 */
public final class SessionLineageGraph {
  private SessionLineageGraph() {}

  private static TaskFailure corrupt() {
    return new TaskFailure(409, SessionErrorCode.SESSION_HISTORY_CORRUPT);
  }

  public static void validate(
      Map<String, Genesis> graph, Map<String, Node> nodes, LongConsumer operations) {
    if (graph.isEmpty() || graph.size() > 9 || !graph.keySet().equals(nodes.keySet()))
      throw corrupt();
    int edges = 0;
    for (var g : graph.values()) {
      operations.accept(1);
      if ("ROOT".equals(g.kind())) {
        if (g.parentSessionId() != null || g.parentGenesisSha256() != null) throw corrupt();
      } else if ("CONTINUATION".equals(g.kind())) {
        var parent = nodes.get(g.parentSessionId());
        if (parent == null
            || !parent.genesisSha256().equals(g.parentGenesisSha256())
            || ++edges > 8) throw corrupt();
      } else throw corrupt();
      var seen = new HashSet<String>();
      var current = g;
      while (current != null) {
        operations.accept(1);
        if (!seen.add(current.sessionId()) || seen.size() > 9) throw corrupt();
        current = current.parentSessionId() == null ? null : graph.get(current.parentSessionId());
      }
    }
  }

  private static PublicNode node(Genesis g, Map<String, Node> nodes) {
    return new PublicNode(
        g.sessionId(),
        g.kind(),
        g.createdAt(),
        nodes.get(g.sessionId()).genesisSha256(),
        g.parentSessionId());
  }

  public static Result trace(
      String caller,
      String target,
      String authority,
      String digest,
      Map<String, Genesis> graph,
      Map<String, Node> nodes,
      LongConsumer operations) {
    var ancestors = new ArrayList<PublicNode>();
    var root = graph.get(target);
    while (root.parentSessionId() != null) {
      operations.accept(1);
      root = graph.get(root.parentSessionId());
      ancestors.add(node(root, nodes));
    }
    var descendants = new ArrayList<Descendant>();
    descendants(target, 1, graph, nodes, descendants, operations);
    return new Result(
        caller,
        authority,
        digest,
        node(graph.get(target), nodes),
        node(root, nodes),
        ancestors,
        descendants);
  }

  private static void descendants(
      String parent,
      int depth,
      Map<String, Genesis> graph,
      Map<String, Node> nodes,
      ArrayList<Descendant> out,
      LongConsumer operations) {
    for (var g :
        graph.values().stream()
            .filter(g -> parent.equals(g.parentSessionId()))
            .sorted(Comparator.comparing(Genesis::createdAt).thenComparing(Genesis::sessionId))
            .toList()) {
      operations.accept(graph.size());
      if (depth > 8 || out.size() >= 8) throw corrupt();
      out.add(new Descendant(node(g, nodes), depth));
      descendants(g.sessionId(), depth + 1, graph, nodes, out, operations);
    }
  }
}
