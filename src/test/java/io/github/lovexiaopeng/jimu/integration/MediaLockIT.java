package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Focused private MySQL regression; no live paid vision or external service credentials. */
class MediaLockIT {
  @Test
  void mediaLocksAndAtomicAccountingMatchTheExactBaseline() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 is required");
    Path baseline = Path.of("target/xiao60/old/media-ff52841.jar");
    assertTrue(Files.isRegularFile(baseline), "Retain the ff52841 tested binary before packaging");
    Path evidence = Path.of("target/failsafe-reports/media-lock");
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
            linux(Path.of("tools/xiao60_media_lock_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--old-jar",
            linux(baseline),
            "--evidence",
            linux(evidence)));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(evidence.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(
          child.waitFor(8, TimeUnit.MINUTES), "Directed lock fixture finishes in foreground");
      assertEquals(0, child.exitValue(), "See media-lock/verify.log and retained failure evidence");
      String cleanup = Files.readString(evidence.resolve("cleanup.json"));
      for (String fact :
          List.of("allChildrenExited", "containerRemoved", "fixtureStopped", "rootRemoved"))
        assertTrue(cleanup.contains("\"" + fact + "\": true"), fact);
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
