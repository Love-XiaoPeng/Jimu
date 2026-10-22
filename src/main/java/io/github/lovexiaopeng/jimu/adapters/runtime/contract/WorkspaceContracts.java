package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Only packaged entries, never model-selected classes or paths. */
public final class WorkspaceContracts {
  public static final List<String> NAMES =
      List.of("read", "write", "present", "compose_report", "edit", "glob", "grep");
  private static final Map<String, String> ENTRIES =
      Map.of(
          "read",
          "ReadPlugin",
          "write",
          "WritePlugin",
          "present",
          "PresentPlugin",
          "compose_report",
          "ComposeReportPlugin",
          "edit",
          "EditPlugin",
          "glob",
          "GlobPlugin",
          "grep",
          "GrepPlugin");

  public record Contract(
      ToolDescriptor descriptor, String input, SchemaContract in, SchemaContract out) {}

  public Contract load(String name) throws IOException {
    return load(name, "0.1.0");
  }

  public Contract load(String name, String version) throws IOException {
    boolean image = name.equals("read_image");
    boolean composeV2 = name.equals("compose_report") && version.equals("0.2.0");
    if ((!NAMES.contains(name) && !image) || !(version.equals("0.1.0") || composeV2))
      throw new IllegalArgumentException("Unknown workspace tool version");
    String root = "plugins/builtin/" + name + (composeV2 ? "-0.2.0" : "") + "/";
    boolean search = name.equals("glob") || name.equals("grep");
    byte[] input = resource(root + "input.schema.json");
    byte[] output = resource(root + "output.schema.json");
    String entry =
        "io.github.lovexiaopeng.jimu.plugins.builtin.workspace."
            + (image ? "ReadImagePlugin" : ENTRIES.get(name));
    String plugin = name.equals("present") ? "jimu.artifact.present" : "jimu.workspace." + name;
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setCodePointLimit(16384);
    Object manifest =
        new Yaml(new SafeConstructor(options))
            .load(new String(resource(root + "plugin.yaml"), StandardCharsets.UTF_8));
    var expected =
        Map.ofEntries(
            Map.entry("manifestVersion", 1),
            Map.entry("id", plugin),
            Map.entry("version", version),
            Map.entry("kind", "tool"),
            Map.entry("hostApi", ">=0.1.0 <0.2.0"),
            Map.entry("entry", Map.of("type", "trusted-jvm", "class", entry)),
            Map.entry(
                "capabilities",
                List.of(
                    Map.of(
                        "name",
                        name,
                        "inputSchema",
                        "input.schema.json",
                        "outputSchema",
                        "output.schema.json",
                        "sideEffect",
                        name.equals("read") || search || image ? "read" : "write"))),
            Map.entry("permissions", Map.of("filesystem", List.of("host-task-workspace"))),
            Map.entry("secretRefs", List.of()),
            Map.entry("dependencies", List.of()),
            Map.entry(
                "limits",
                Map.of(
                    "timeoutMs", 30000, "outputBytes", image ? 16384 : search ? 65536 : 1048576)),
            Map.entry(
                "healthCheck",
                Map.of(
                    "operation",
                    image
                        ? "image-store-readiness"
                        : search ? "native-search-readiness" : "workspace-readiness",
                    "timeoutMs",
                    2000)),
            Map.entry("errorContract", "jimu-invocation-result"),
            Map.entry(
                "eventTypes",
                List.of("invocation.started", "invocation.completed", "invocation.failed")));
    if (!expected.equals(manifest))
      throw new IllegalArgumentException("Invalid workspace manifest");
    try {
      var descriptor =
          new ToolDescriptor(
              plugin,
              version,
              name,
              root + "input.schema.json",
              root + "output.schema.json",
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output)),
              entry);
      return new Contract(
          descriptor,
          new String(input, StandardCharsets.UTF_8),
          new SchemaContract(input),
          new SchemaContract(output));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private byte[] resource(String path) throws IOException {
    try (var stream = WorkspaceContracts.class.getClassLoader().getResourceAsStream(path)) {
      if (stream == null) throw new IOException("Missing workspace contract");
      return stream.readAllBytes();
    }
  }
}
