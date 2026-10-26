package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Bounded private committed fixture for the three named gaps only. */
class TransactionGapsIT {
  @Test
  void finalBeforeCommitScopedReadReplayAndDurableFailureNote() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 required");
    Path folder =
        Path.of(
            "target/failsafe-reports/transaction-gaps",
            System.getProperty("jimu.gaps.round", "run-" + System.currentTimeMillis()));
    Files.createDirectories(folder);
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
            linux(Path.of("tools/xiao60_transaction_gaps_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--evidence",
            linux(folder)));
    if (System.getProperty("jimu.gaps.reuse-g01") != null)
      command.addAll(
          List.of("--reuse-g01", linux(Path.of(System.getProperty("jimu.gaps.reuse-g01")))));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(folder.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(child.waitFor(8, TimeUnit.MINUTES), "Bounded G01-G03 private verification");
      assertEquals(0, child.exitValue(), "See transaction-gaps/verify.log");
      String cleanup = Files.readString(folder.resolve("cleanup.json"));
      assertTrue(
          cleanup.contains("\"allChildrenExited\": true")
              && cleanup.contains("\"containerRemoved\": true"));
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(20, TimeUnit.SECONDS)) child.destroyForcibly();
        child.waitFor();
      }
    }
  }

  private static String linux(Path path) {
    String full = path.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(full.charAt(0)) + full.substring(2)
        : full;
  }
}
