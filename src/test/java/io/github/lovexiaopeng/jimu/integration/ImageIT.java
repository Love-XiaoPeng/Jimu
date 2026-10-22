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

class ImageIT {
  @Test
  void nativeStoreAndControlledCallStreamRecoveryCancellation() throws Exception {
    Path evidence = Path.of("target/failsafe-reports/image-evidence");
    Files.createDirectories(evidence);
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    String jar = nativePath(Path.of(System.getProperty("jimu.jar")), windows);
    var commands =
        List.of(
            List.of(
                nativePath(Path.of("tools/image_store_verify.py"), windows),
                jar,
                nativePath(Path.of("target/test-classes"), windows),
                nativePath(evidence.resolve("store"), windows)),
            List.of(
                nativePath(Path.of("tools/p4_image_verify.py"), windows),
                "--jar",
                jar,
                "--evidence",
                nativePath(evidence.resolve("vertical"), windows)));
    for (int i = 0; i < commands.size(); i++) {
      var command = new ArrayList<String>();
      command.addAll(
          windows
              ? List.of("wsl.exe", "-d", "Ubuntu", "--", "python3", "-u")
              : List.of("python3", "-u"));
      command.addAll(commands.get(i));
      Path log = evidence.resolve("runner-" + i + ".log");
      var process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      try {
        assertTrue(process.waitFor(8, TimeUnit.MINUTES), "Image scenarios complete in foreground");
        assertEquals(
            0,
            process.exitValue(),
            () -> {
              try {
                return Files.readString(log);
              } catch (java.io.IOException error) {
                return error.toString();
              }
            });
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
    assertTrue(
        Files.readString(evidence.resolve("store/cleanup.json"))
            .contains("\"nativeRootRemoved\": true"));
    assertTrue(Files.exists(evidence.resolve("vertical/cleanup.json")));
  }

  private static String nativePath(Path path, boolean windows) {
    String value = path.toAbsolutePath().toString().replace('\\', '/');
    return windows ? "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2) : value;
  }
}
