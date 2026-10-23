package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** B0/B1 同制品私有 MySQL 验证入口，缺旧制品/Java21 明确失败。 */
class PersistenceFoundationIT {
  @Test
  void changedCoreQuestionTodoAndDirectoryHooksUseSameProduct() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    Path evidence = Path.of("target/failsafe-reports/core-module-hooks");
    Files.createDirectories(evidence);
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
            linux(Path.of("tools/xiao60_core_modules_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--evidence",
            linux(evidence)));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(child.waitFor(10, TimeUnit.MINUTES), "Changed module hooks finish in foreground");
      assertEquals(0, child.exitValue(), "See core-module-hooks/verify.log");
      for (var module : List.of("questions", "todos", "directory")) {
        String cleanup = Files.readString(evidence.resolve(module).resolve("cleanup.json"));
        assertTrue(
            cleanup.contains("\"allChildrenExited\": true")
                && cleanup.contains("\"containerRemoved\": true"),
            module);
      }
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(20, TimeUnit.SECONDS)) child.destroyForcibly();
        child.waitFor();
      }
    }
  }

  private static String linux(Path file) {
    String full = file.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(full.charAt(0)) + full.substring(2)
        : full;
  }

  @Test
  void productAssemblyTransactionsAndOldDataAreVerifiedOnPrivateMysql() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    assertTrue(System.getenv("JIMU_XIAO55_JAR") != null, "Accepted XIAO-55 binary is required");
    var old = Path.of(System.getenv("JIMU_XIAO55_JAR"));
    assertTrue(Files.isRegularFile(old));
    Path evidence = Path.of("target/failsafe-reports/persistence-foundation");
    Files.createDirectories(evidence);
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
            linux(Path.of("tools/xiao60_foundation_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--old-jar",
            linux(old),
            "--evidence",
            linux(evidence)));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(child.waitFor(6, TimeUnit.MINUTES), "B0/B1 fixture completes in foreground");
      assertEquals(0, child.exitValue(), "See persistence-foundation/verify.log and failure.txt");
      String cleanup = Files.readString(evidence.resolve("cleanup.json"));
      assertTrue(
          cleanup.contains("\"allChildrenExited\": true")
              && cleanup.contains("\"containerRemoved\": true"));
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(20, TimeUnit.SECONDS)) {
          child.destroyForcibly();
          child.waitFor();
        }
      }
    }
  }
}
