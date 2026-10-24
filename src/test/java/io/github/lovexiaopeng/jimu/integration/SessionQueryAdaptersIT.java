package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SessionQueryAdaptersIT {
  @Test
  void affectedLegacyReadSearchTraceOnSameArtifact() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    Path evidence = Path.of("target/failsafe-reports/session-query-legacy");
    Files.createDirectories(evidence);
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").startsWith("Windows"))
      command.addAll(
          List.of(
              "wsl",
              "-d",
              "Ubuntu",
              "--exec",
              "env",
              "JIMU_IT_JAVA=" + System.getenv("JIMU_IT_JAVA")));
    command.addAll(
        List.of(
            "python3",
            "-B",
            "-u",
            linux(Path.of("tools/prior_session_legacy_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--evidence",
            linux(evidence),
            "--case",
            "read",
            "--case",
            "search",
            "--case",
            "trace"));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(
          child.waitFor(10, TimeUnit.MINUTES), "Affected legacy fixtures finish in foreground");
      assertEquals(0, child.exitValue(), "See session-query-legacy/verify.log and failure.txt");
      String results = Files.readString(evidence.resolve("result.json"));
      assertTrue(results.contains("\"trace\"") && !results.contains("\"exit\": 1"));
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(20, TimeUnit.SECONDS)) child.destroyForcibly();
        child.waitFor();
      }
    }
  }

  private static String linux(Path file) {
    String full = file.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(full.charAt(0)) + full.substring(2)
        : full;
  }

  @Test
  void explicitQueryActualProofsAndDeadlinesOnPrivateMysql() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    assertTrue(System.getenv("JIMU_XIAO55_JAR") != null, "Accepted binary is required");
    Path evidence = Path.of("target/failsafe-reports/session-query-adapters");
    Files.createDirectories(evidence);
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").startsWith("Windows"))
      command.addAll(
          List.of(
              "wsl",
              "-d",
              "Ubuntu",
              "--exec",
              "env",
              "JIMU_IT_JAVA=" + System.getenv("JIMU_IT_JAVA")));
    command.addAll(
        List.of(
            "python3",
            "-B",
            "-u",
            linux(Path.of("tools/xiao60_query_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--old-jar",
            linux(Path.of(System.getenv("JIMU_XIAO55_JAR"))),
            "--evidence",
            linux(evidence)));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(child.waitFor(6, TimeUnit.MINUTES), "Query fixture finishes in foreground");
      assertEquals(0, child.exitValue(), "See session-query-adapters/verify.log and failure.txt");
      String cleanup = Files.readString(evidence.resolve("cleanup.json"));
      assertTrue(
          cleanup.contains("\"allChildrenExited\": true")
              && cleanup.contains("\"containerRemoved\": true"));
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(20, TimeUnit.SECONDS)) {
          child.destroyForcibly();
          child.waitFor();
        }
      }
    }
  }
}
