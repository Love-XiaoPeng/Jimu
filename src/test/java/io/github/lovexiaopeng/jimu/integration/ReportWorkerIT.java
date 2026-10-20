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

class ReportWorkerIT {
  @Test
  void reportProductionBindsOwnInvocationAndAuthorizedDownload() throws Exception {
    verify("report-worker-evidence");
  }

  @Test
  void reportCitationGenerationAndDownload() throws Exception {
    verify("report-citation-evidence", "--citations-only");
  }

  private void verify(String name, String... options) throws Exception {
    Path evidence = Path.of("target/failsafe-reports", name);
    Files.createDirectories(evidence);
    Path script = Path.of("tools/p3_report_verify.py").toAbsolutePath(),
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
    command.addAll(List.of(options));
    var log = evidence.resolve("runner.log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(
          process.waitFor(15, TimeUnit.MINUTES),
          "Report worker integration must finish in foreground");
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
