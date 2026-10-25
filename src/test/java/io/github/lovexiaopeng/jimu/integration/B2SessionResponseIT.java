package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** B2 实际受影响切片；独立私有库、同版 JAR，缺资源失败，所有子进程前台收回。 */
class B2SessionResponseIT {
  @Test
  void sessionResponse() throws Exception {
    run(
        "native",
        "tools/xiao60_b2_session_response_verify.py",
        "--old-jar",
        linux(Path.of("../XIAO-60-b2-session-response-baseline.jar")));
  }

  private static void run(String name, String script, String... extra) throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    Path evidence =
        Path.of(
            "target/failsafe-reports",
            System.getProperty("jimu.sessionResponse.evidence", "b2-session-response"),
            name);
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
            linux(Path.of(script)),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--evidence",
            linux(evidence)));
    command.addAll(List.of(extra));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(
          child.waitFor(30, TimeUnit.MINUTES), "Changed slice finishes in foreground: " + name);
      assertEquals(0, child.exitValue(), "See b2-session-response/" + name + "/verify.log");
      try (var files = Files.walk(evidence)) {
        var binding = files.filter(p -> p.getFileName().toString().equals("cleanup.json")).toList();
        assertTrue(!binding.isEmpty(), "Actual cleanup binding are required");
        for (var receipt : binding) {
          String cleanup = Files.readString(receipt);
          assertTrue(
              cleanup.contains("\"allChildrenExited\": true")
                  && cleanup.contains("\"containerRemoved\": true"),
              receipt.toString());
        }
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
}
