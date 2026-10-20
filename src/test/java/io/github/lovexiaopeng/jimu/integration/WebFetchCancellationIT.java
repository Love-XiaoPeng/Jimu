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

class WebFetchCancellationIT {
  @Test
  void cancellationObservationSurvivesOriginalFenceAndRecovery() throws Exception {
    Path evidence = Path.of("target/failsafe-reports/web-cancellation-evidence").toAbsolutePath();
    Files.createDirectories(evidence);
    var command = new ArrayList<String>();
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    if (windows) command.addAll(List.of("wsl.exe", "-d", "Ubuntu", "--"));
    command.addAll(List.of("python3", "-u"));
    for (Path path :
        List.of(
            Path.of("tools/p3_web_cancel_verify.py"),
            Path.of(System.getProperty("jimu.jar")),
            evidence,
            Path.of("target/test-classes"))) {
      String value = path.toAbsolutePath().toString().replace('\\', '/');
      command.add(
          windows ? "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2) : value);
    }
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("runner.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(8, TimeUnit.MINUTES));
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(evidence.resolve("runner.log"));
            } catch (java.io.IOException failure) {
              return "Cancellation evidence unavailable";
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
