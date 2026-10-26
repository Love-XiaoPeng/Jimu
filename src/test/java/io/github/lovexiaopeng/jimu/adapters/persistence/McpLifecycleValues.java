package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.mcp.McpContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.McpLedger;
import io.github.lovexiaopeng.jimu.plugin.api.McpResourcePort;
import java.util.List;
import java.util.Map;

/** 显式合法已存结果，无MCP或工具执行。 */
final class McpLifecycleValues {
  static final String CURSOR = "abcdefghijklmnopqrstuv";

  static McpLedger.Profile profile(String host) {
    return new McpLedger.Profile(
        1,
        "tenant-a",
        "controlled",
        1,
        host,
        "http://127.0.0.1:1/mcp",
        "fixture",
        1,
        List.of("alice"),
        List.of(),
        List.of("jimu-fixture://tenant-a/docs/"),
        List.of("jimu-fixture://tenant-a/docs/{name}"),
        "2025-06-18",
        new McpLedger.ServerInfo("jimu-controlled", "1"),
        Map.of("resources", Map.of()),
        McpContracts.DIGEST);
  }

  static McpLedger.ListSnapshot list(
      String task,
      String invocation,
      String binding,
      String query,
      McpLedger.Server server,
      boolean templates) {
    List<?> values =
        templates
            ? List.of(
                new McpResourcePort.Template(
                    "jimu-fixture://tenant-a/docs/{name}", "模板一", null, null, null),
                new McpResourcePort.Template(
                    "jimu-fixture://tenant-a/docs/{other}", "模板二", null, null, null))
            : List.of(
                new McpResourcePort.Resource(
                    "jimu-fixture://tenant-a/docs/one", "资源一", null, null, null, null),
                new McpResourcePort.Resource(
                    "jimu-fixture://tenant-a/docs/two", "资源二", null, null, null, null));
    String projection = Payloads.hash(Payloads.json(values));
    var pages = new java.util.ArrayList<String>();
    for (int i = 0; i < 2; i++) {
      String next = i == 0 ? CURSOR : null;
      pages.add(
          Payloads.json(
              templates
                  ? new McpResourcePort.Templates(
                      1,
                      "controlled",
                      "1",
                      server.digest(),
                      server.profile().schemaDigest(),
                      "untrusted-mcp-content-v1",
                      invocation,
                      projection,
                      List.of((McpResourcePort.Template) values.get(i)),
                      i,
                      1,
                      2,
                      next)
                  : new McpResourcePort.Resources(
                      1,
                      "controlled",
                      "1",
                      server.digest(),
                      server.profile().schemaDigest(),
                      "untrusted-mcp-content-v1",
                      invocation,
                      projection,
                      List.of((McpResourcePort.Resource) values.get(i)),
                      i,
                      1,
                      2,
                      next)));
    }
    return new McpLedger.ListSnapshot(
        1,
        "tenant-a",
        "alice",
        task,
        invocation,
        "controlled",
        templates ? "resources/templates/list" : "resources/list",
        binding,
        query,
        projection,
        pages,
        Map.of(Payloads.hash(CURSOR), 1));
  }
}
