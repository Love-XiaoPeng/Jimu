package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UserQuestionIT {
  private static String linuxPath(Path path) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
  }

  @Test
  void questionsUseAuthenticatedHttpAndDurableWorkerRecovery() throws Exception {
    Path evidence =
        Path.of(
            "target/failsafe-reports/question-evidence", java.util.UUID.randomUUID().toString());
    Files.createDirectories(evidence);
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    var command = new ArrayList<String>();
    String java = System.getenv("JIMU_IT_JAVA");
    assertTrue(java != null && !java.isBlank(), "JIMU_IT_JAVA requires local Linux Java21");
    if (windows) command.addAll(List.of("wsl", "--exec", "env", "JIMU_IT_JAVA=" + java));
    command.addAll(List.of("python3", "-B", "-u"));
    Path driver = Path.of("tools/user_question_verify.py"),
        jar = Path.of(System.getProperty("jimu.jar"));
    command.add(windows ? linuxPath(driver) : driver.toString());
    command.addAll(
        List.of(
            "--jar",
            windows ? linuxPath(jar) : jar.toString(),
            "--evidence",
            windows ? linuxPath(evidence) : evidence.toString()));
    String oldJar = System.getenv("JIMU_V15_JAR");
    assertTrue(
        oldJar != null && Files.isRegularFile(Path.of(oldJar)),
        "JIMU_V15_JAR must name the built exact V15 master binary");
    command.addAll(List.of("--v15-jar", windows ? linuxPath(Path.of(oldJar)) : oldJar));
    Process process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("driver.log").toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.MINUTES), "Question slice finishes in foreground");
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
          Files.readString(evidence.resolve("cleanup.json"))
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
