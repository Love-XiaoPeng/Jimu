package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class P4WorkspaceWorkerIT {
  @Test
  void requiredP4CasesUseRealNonrootPosixNativeProcessesAndMysql() throws Exception {
    String bundle = System.getenv("JIMU_NATIVE_BUNDLE");
    assertTrue(
        bundle != null && Files.isRegularFile(Path.of(bundle)),
        "JIMU_NATIVE_BUNDLE must name a reviewed offline bundle manifest; missing native resources fail");
    Path evidence =
        Path.of("target/failsafe-reports/p4-evidence", java.util.UUID.randomUUID().toString());
    Files.createDirectories(evidence);
    Process process =
        new ProcessBuilder(
                List.of(
                    "python3",
                    "-u",
                    "tools/p4_workspace_verify.py",
                    "--case",
                    "ALL",
                    "--jar",
                    System.getProperty("jimu.jar"),
                    "--test-classes",
                    "target/test-classes",
                    "--bundle",
                    bundle,
                    "--evidence",
                    evidence.toString()))
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("driver.log").toFile())
            .start();
    try {
      assertTrue(
          process.waitFor(12, TimeUnit.MINUTES), "P4 required matrix finishes in foreground");
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
          Files.readString(evidence.resolve("persistent/cleanup.json"))
              .contains("\"allChildrenExited\": true"));
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
