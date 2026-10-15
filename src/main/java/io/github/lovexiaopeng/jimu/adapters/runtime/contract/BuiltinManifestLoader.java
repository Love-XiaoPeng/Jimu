package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Allows only the reviewed, packaged built-in. Manifest class names are never dynamically loaded.
 */
public final class BuiltinManifestLoader {
  public static final String ROOT = "plugins/builtin/webfetch/";
  public static final String OUTPUT_SHA256 =
      "08a144489ed9ea37b5b33a5ffdc815237f31d4f229b76ba1d5e465b9e13f9ff8";
  private static final String ENTRY =
      "io.github.lovexiaopeng.jimu.plugins.builtin.webfetch.WebFetchPlugin";

  public ToolDescriptor load() throws IOException {
    byte[] output = resource("output.schema.json");
    if (!OUTPUT_SHA256.equals(sha256(output)))
      throw new IllegalArgumentException("Canonical output Schema digest mismatch");
    new SchemaContract(output);
    new SchemaContract(resource("input.schema.json"));
    return parse(resource("plugin.yaml"));
  }

  public ToolDescriptor parse(byte[] yaml) {
    var options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setCodePointLimit(16384);
    Object loaded =
        new Yaml(new SafeConstructor(options))
            .load(new String(yaml, java.nio.charset.StandardCharsets.UTF_8));
    if (!(loaded instanceof Map<?, ?> manifest))
      throw new IllegalArgumentException("Manifest must be an object");
    expect(manifest, "manifestVersion", 1);
    expect(manifest, "id", "jimu.web.fetch");
    expect(manifest, "version", "0.2.0");
    expect(manifest, "kind", "tool");
    expect(manifest, "hostApi", ">=0.1.0 <0.2.0");
    expect(manifest, "entry", Map.of("type", "trusted-jvm", "class", ENTRY));
    expect(
        manifest,
        "capabilities",
        List.of(
            Map.of(
                "name",
                "web_fetch",
                "inputSchema",
                "input.schema.json",
                "outputSchema",
                "output.schema.json",
                "sideEffect",
                "read")));
    expect(manifest, "permissions", Map.of("network", List.of("configured-origin-only")));
    expect(manifest, "limits", Map.of("timeoutMs", 30000, "outputBytes", 1048576));
    expect(manifest, "secretRefs", List.of());
    expect(manifest, "dependencies", List.of());
    expect(manifest, "healthCheck", Map.of("operation", "provider-readiness", "timeoutMs", 2000));
    expect(manifest, "errorContract", "jimu-invocation-result");
    expect(
        manifest,
        "eventTypes",
        List.of("invocation.started", "invocation.completed", "invocation.failed"));
    if (manifest.size() != 14) throw new IllegalArgumentException("Unknown manifest fields");
    return new ToolDescriptor(
        "jimu.web.fetch",
        "0.2.0",
        "web_fetch",
        ROOT + "input.schema.json",
        ROOT + "output.schema.json",
        OUTPUT_SHA256,
        ENTRY);
  }

  private static void expect(Map<?, ?> manifest, String key, Object expected) {
    if (!expected.equals(manifest.get(key)))
      throw new IllegalArgumentException("Invalid built-in manifest field: " + key);
  }

  public static byte[] resource(String name) throws IOException {
    try (InputStream stream =
        BuiltinManifestLoader.class.getClassLoader().getResourceAsStream(ROOT + name)) {
      if (stream == null) throw new IOException("Missing required built-in resource: " + name);
      return stream.readAllBytes();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
