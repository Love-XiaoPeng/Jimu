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

class PublicWorkerIT {
  @Test
  void publicRuntimeUsesLocalTlsAndPersistedInvocationRecovery() throws Exception {
    Path evidence = Path.of("target/failsafe-reports/public-worker-evidence");
    Files.createDirectories(evidence);
    Path script = Path.of("tools/p3_public_worker_verify.py").toAbsolutePath(),
        jar = Path.of(System.getProperty("jimu.jar")).toAbsolutePath();
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
      command.addAll(List.of("wsl.exe", "-d", "Ubuntu", "--", "python3", "-u"));
      for (Path path :
          List.of(
              script,
              jar,
              evidence.toAbsolutePath(),
              Path.of("target/test-classes").toAbsolutePath())) {
        String value = path.toString().replace('\\', '/');
        command.add("/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2));
      }
    } else
      command.addAll(
          List.of(
              "python3",
              "-u",
              script.toString(),
              jar.toString(),
              evidence.toAbsolutePath().toString(),
              Path.of("target/test-classes").toAbsolutePath().toString()));
    var log = evidence.resolve("runner.log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(
          process.waitFor(10, TimeUnit.MINUTES),
          "Public worker integration must finish in foreground");
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
