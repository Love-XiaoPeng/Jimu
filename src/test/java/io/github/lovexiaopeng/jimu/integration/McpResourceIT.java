package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpResourceIT {
  private static String linuxPath(Path path) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
  }

  @Test
  void resourceContractUsesActualControlWorkerMysqlAndPrivateMcpService() throws Exception {
    Path evidence =
        Path.of("target/failsafe-reports/mcp-evidence", java.util.UUID.randomUUID().toString());
    Files.createDirectories(evidence);
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    var command = new ArrayList<String>();
    if (windows) command.addAll(List.of("wsl", "--exec", "env"));
    String java = System.getenv("JIMU_IT_JAVA");
    assertTrue(
        java != null && !java.isBlank(),
        "JIMU_IT_JAVA must name a reviewed local Linux Java21 runtime");
    if (windows) command.add("JIMU_IT_JAVA=" + java);
    command.add("python3");
    Path driver = Path.of("tools/mcp_verify.py"), jar = Path.of(System.getProperty("jimu.jar"));
    command.add(windows ? linuxPath(driver) : driver.toString());
    command.addAll(
        List.of(
            "--jar",
            windows ? linuxPath(jar) : jar.toString(),
            "--evidence",
            windows ? linuxPath(evidence) : evidence.toString()));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("driver.log").toFile())
            .start();
    try {
      assertTrue(
          process.waitFor(20, TimeUnit.MINUTES), "MCP required integration finishes in foreground");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(evidence.resolve("driver.log"));
            } catch (java.io.IOException error) {
              return error.toString();
            }
          });
      assertTrue(
          Files.readString(evidence.resolve("mcp-cleanup.json")).contains("\"portClosed\": true"));
    } finally {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor();
        }
      }
    }
  }
}
