package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkingDirectoryIT {
  private static String path(Path path) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2)
        : value;
  }

  @Test
  void realDirectoryAcceptance() throws Exception {
    String cases =
        System.getProperty(
            "jimu.directory.cases", "D01,D02,D03,D04,D05,D06,D07,D08,D09,D10,D11,D12");
    String old = System.getenv("JIMU_V17_JAR");
    Path evidence =
        Path.of("target/failsafe-reports/directory-evidence", UUID.randomUUID().toString());
    for (String test : cases.split(",")) {
      assertTrue(test.matches("D(0[1-9]|1[0-2])"), "Unknown directory acceptance case");
      Path folder = evidence.resolve(test);
      Files.createDirectories(folder);
      var command = new ArrayList<String>();
      if (System.getProperty("os.name").startsWith("Windows"))
        command.addAll(List.of("wsl", "--exec"));
      command.addAll(
          List.of(
              "python3",
              "-B",
              "-u",
              path(Path.of("tools/working_directory_verify.py")),
              "--jar",
              path(Path.of(System.getProperty("jimu.jar"))),
              "--evidence",
              path(folder),
              "--case",
              test));
      if (test.equals("D11")) {
        assertTrue(
            old != null && Files.isRegularFile(Path.of(old)),
            "D11 requires JIMU_V17_JAR from exact old master source");
        command.addAll(List.of("--v17-jar", path(Path.of(old))));
      }
      var process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(folder.resolve("driver.log").toFile())
              .start();
      try {
        assertTrue(
            process.waitFor(10, TimeUnit.MINUTES),
            "Directory case finishes in foreground: " + test);
        assertEquals(
            0,
            process.exitValue(),
            () -> {
              try {
                return Files.readString(folder.resolve("driver.log"));
              } catch (java.io.IOException error) {
                return error.toString();
              }
            });
        assertTrue(
            Files.readString(folder.resolve("cleanup.json"))
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
}
