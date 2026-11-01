package io.github.lovexiaopeng.jimu.kernel.session.api;

import java.util.List;

/** 不含框架的平铺关系证明；父ID只进入可精确清理的正文快照。 */
public final class SessionLineage {
  private SessionLineage() {}

  public record Genesis(
      int version,
      String sessionId,
      String kind,
      String createdAt,
      String requestSha256,
      String authoritySha256,
      String bindingSha256,
      String parentSessionId,
      String parentGenesisSha256) {}

  public record Node(String sessionId, String genesisSnapshot, String genesisSha256) {}

  public record Graph(
      int version, String callerSessionId, String authoritySha256, List<Node> nodes) {
    public Graph {
      nodes = List.copyOf(nodes);
    }
  }

  public record PublicNode(
      String sessionId,
      String kind,
      String createdAt,
      String genesisSha256,
      String parentSessionId) {}

  public record Descendant(PublicNode node, int depth) {}

  public record Result(
      String caller,
      String authority,
      String graph,
      PublicNode target,
      PublicNode root,
      List<PublicNode> ancestors,
      List<Descendant> descendants) {
    public Result {
      ancestors = List.copyOf(ancestors);
      descendants = List.copyOf(descendants);
    }
  }

  public record Stored(
      String genesis,
      String genesisDigest,
      long genesisBytes,
      String graph,
      String graphDigest,
      long graphBytes,
      String state,
      long version) {}
}
