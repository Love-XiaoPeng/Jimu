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

class TodoIT {
  private static String path(Path path) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2)
        : value;
  }

  @Test
  void realTodoLoopAndOriginalV16Migration() throws Exception {
    String java = System.getenv("JIMU_IT_JAVA"), old = System.getenv("JIMU_V16_JAR");
    assertTrue(java != null && !java.isBlank(), "JIMU_IT_JAVA requires Linux Java21");
    assertTrue(
        old != null && Files.isRegularFile(Path.of(old)),
        "JIMU_V16_JAR requires exact old master artifact");
    Path evidence = Path.of("target/failsafe-reports/todo-evidence", UUID.randomUUID().toString());
    Files.createDirectories(evidence);
    for (String script : List.of("todo_verify.py", "todo_migration_verify.py")) {
      Path folder = evidence.resolve(script.replace(".py", ""));
      Files.createDirectories(folder);
      var command = new ArrayList<String>();
      if (System.getProperty("os.name").startsWith("Windows"))
        command.addAll(List.of("wsl", "--exec", "env", "JIMU_IT_JAVA=" + java));
      command.addAll(
          List.of(
              "python3",
              "-B",
              "-u",
              path(Path.of("tools", script)),
              "--jar",
              path(Path.of(System.getProperty("jimu.jar"))),
              "--evidence",
              path(folder)));
      if (script.equals("todo_migration_verify.py"))
        command.addAll(List.of("--v16-jar", path(Path.of(old))));
      var process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(folder.resolve("driver.log").toFile())
              .start();
      try {
        assertTrue(process.waitFor(20, TimeUnit.MINUTES), "Todo validation finishes in foreground");
        assertEquals(
            0,
            process.exitValue(),
            () -> {
              try {
                return Files.readString(folder.resolve("driver.log"));
              } catch (java.io.IOException e) {
                return e.toString();
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
