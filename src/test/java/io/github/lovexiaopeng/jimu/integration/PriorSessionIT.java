package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** New scope lifecycle and affected legacy slices share the same packaged production JAR. */
class PriorSessionIT {
  private static String path(Path value) {
    String full = value.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(full.charAt(0)) + full.substring(2)
        : full;
  }

  private static List<String> command(String script, Path evidence) {
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").startsWith("Windows")) {
      command.addAll(List.of("wsl", "-d", "Ubuntu", "--exec", "env"));
      command.add("JIMU_IT_JAVA=" + System.getenv("JIMU_IT_JAVA"));
    }
    command.addAll(
        List.of(
            "python3",
            "-B",
            "-u",
            path(Path.of("tools", script)),
            "--jar",
            path(Path.of(System.getProperty("jimu.jar"))),
            "--evidence",
            path(evidence)));
    return command;
  }

  @Test
  void realPriorScopeAndAffectedLegacyCapabilities() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    assertTrue(System.getenv("JIMU_V21_JAR") != null, "Exact V21 master JAR is required");
    Path old = Path.of(System.getenv("JIMU_V21_JAR"));
    assertTrue(Files.isRegularFile(old), "Exact V21 master JAR must exist");
    Path evidence = Path.of("target/failsafe-reports/prior-session-evidence");
    Files.createDirectories(evidence);
    var core = command("prior_session_verify.py", evidence.resolve("core"));
    core.addAll(List.of("--v21-jar", path(old)));
    var legacy = command("prior_session_legacy_verify.py", evidence.resolve("legacy"));
    for (String name :
        List.of("read", "search", "trace", "todo", "questions", "directory", "workspace-mcp"))
      legacy.addAll(List.of("--case", name));
    var children = new ArrayList<Process>();
    try {
      for (var entry : List.of(core, legacy)) {
        String name = entry == core ? "core" : "legacy";
        children.add(
            new ProcessBuilder(entry)
                .redirectErrorStream(true)
                .redirectOutput(evidence.resolve(name + ".log").toFile())
                .start());
      }
      var exits = new ArrayList<Integer>();
      for (var child : children) {
        assertTrue(child.waitFor(20, TimeUnit.MINUTES), "Private fixtures finish in foreground");
        exits.add(child.exitValue());
      }
      assertEquals(List.of(0, 0), exits, "See preserved core.log and legacy.log");
      assertTrue(
          Files.readString(evidence.resolve("core/cleanup.json"))
              .contains("\"allChildrenExited\": true"));
      String results = Files.readString(evidence.resolve("legacy/result.json"));
      assertTrue(results.contains("\"workspace-mcp\"") && !results.contains("\"exit\": 1"));
    } finally {
      for (var child : children)
        if (child.isAlive()) {
          child.destroy();
          if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor();
          }
        }
    }
  }
}
