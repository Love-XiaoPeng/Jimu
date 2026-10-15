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

/** Independent local HTTP endpoints and real MySQL, without model/search accounts. */
class ModelCoreIT {
  private static String linux(Path path) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
  }

  @Test
  void pinnedProfilesProtocolSwitchingAndDurableMoneyCaps() throws Exception {
    Path evidence = Path.of("target/failsafe-reports/model-evidence");
    Files.createDirectories(evidence);
    Path script = Path.of("tools/p3_model_verify.py"),
        jar = Path.of(System.getProperty("jimu.jar"));
    var command = new ArrayList<String>();
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"))
      command.addAll(
          List.of(
              "wsl.exe",
              "-d",
              "Ubuntu",
              "--",
              "python3",
              "-u",
              linux(script),
              linux(jar),
              linux(evidence)));
    else
      command.addAll(
          List.of(
              "python3",
              "-u",
              script.toAbsolutePath().toString(),
              jar.toAbsolutePath().toString(),
              evidence.toAbsolutePath().toString()));
    Path log = evidence.resolve("runner.log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(
          process.waitFor(14, TimeUnit.MINUTES),
          "Model fixture must complete and clean up in foreground");
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
