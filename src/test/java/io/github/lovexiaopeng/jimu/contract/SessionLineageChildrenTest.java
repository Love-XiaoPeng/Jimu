package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Genesis;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionLineage.Node;
import io.github.lovexiaopeng.jimu.kernel.session.domain.SessionLineageGraph;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionLineageChildrenTest {
  @Test
  void oneStableIndexServesRepeatedTargetsIncludingLeaves() {
    var graph = new HashMap<String, Genesis>();
    var nodes = new HashMap<String, Node>();
    for (int i = 0; i < 9; i++) {
      String id = "node" + i;
      graph.put(
          id,
          new Genesis(
              1,
              id,
              i == 0 ? "ROOT" : "CONTINUATION",
              i == 8 ? "later" : "earlier",
              "request",
              "binding",
              "authority",
              i == 0 ? null : "node0",
              i == 0 ? null : "hash"));
      nodes.put(id, new Node(id, "{}", "hash"));
    }
    var build = new java.util.concurrent.atomic.AtomicLong();
    var children = SessionLineageGraph.children(graph, build::addAndGet);
    assertTrue(build.get() >= 9 + 8 + 8);
    assertEquals(
        List.of("node1", "node2", "node3", "node4", "node5", "node6", "node7", "node8"),
        children.get("node0").stream().map(Genesis::sessionId).toList());
    var traverse = new java.util.concurrent.atomic.AtomicLong();
    var root =
        SessionLineageGraph.trace(
            "node0", "node0", "a", "g", graph, nodes, children, traverse::addAndGet);
    assertEquals(8, root.descendants().size());
    assertEquals(17, traverse.get());
    traverse.set(0);
    var leaf =
        SessionLineageGraph.trace(
            "node0", "node8", "a", "g", graph, nodes, children, traverse::addAndGet);
    assertEquals(0, leaf.descendants().size());
    assertEquals(1, leaf.ancestors().size());
    assertEquals(2, traverse.get());
  }
}
