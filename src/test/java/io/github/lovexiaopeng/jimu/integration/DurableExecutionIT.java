package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The same mandatory real MySQL/POSIX/child-process suite runs locally and on CI. */
class DurableExecutionIT {
  private static String linux(Path path) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
  }

  @Test
  void persistentExecutionAuthorizationBudgetsEventsAndArtifacts() throws Exception {
    Path evidence = Path.of("target/failsafe-reports/p2-evidence");
    Files.createDirectories(evidence);
    Path script = Path.of("tools/p2_verify.py"), jar = Path.of(System.getProperty("jimu.jar"));
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      command.addAll(
          List.of(
              "wsl.exe",
              "-d",
              "Ubuntu",
              "--",
              "python3",
              linux(script),
              linux(jar),
              linux(evidence)));
    } else
      command.addAll(
          List.of(
              "python3",
              script.toAbsolutePath().toString(),
              jar.toAbsolutePath().toString(),
              evidence.toAbsolutePath().toString()));
    Path log = evidence.resolve("runner.log");
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(
          process.waitFor(24, TimeUnit.MINUTES),
          "P2 fixture must finish and clean up in foreground");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(log);
            } catch (java.io.IOException e) {
              return e.toString();
            }
          });
      assertTrue(
          Files.readString(evidence.resolve("cleanup.json"))
              .contains("\"allChildrenExited\": true"));
    } finally {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor();
        }
      }
    }
  }
}
