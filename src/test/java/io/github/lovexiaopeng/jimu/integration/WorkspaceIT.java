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

class WorkspaceIT {
  @Test
  void scopedFileOpenAndDurableCasOnNativePosix() throws Exception {
    Path evidence = Path.of("target/failsafe-reports/workspace-evidence");
    Files.createDirectories(evidence);
    var command = new ArrayList<String>();
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    command.addAll(
        windows
            ? List.of("wsl.exe", "-d", "Ubuntu", "--", "python3", "-u")
            : List.of("python3", "-u"));
    for (Path path :
        List.of(
            Path.of("tools/workspace_verify.py"),
            Path.of(System.getProperty("jimu.jar")),
            Path.of("target/test-classes"),
            evidence)) {
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
      assertTrue(
          process.waitFor(5, TimeUnit.MINUTES), "Workspace native scenarios finish in foreground");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(evidence.resolve("runner.log"));
            } catch (java.io.IOException error) {
              return error.toString();
            }
          });
      assertTrue(
          Files.readString(evidence.resolve("cleanup.json"))
              .contains("\"nativeRootRemoved\": true"));
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
