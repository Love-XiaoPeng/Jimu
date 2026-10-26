package io.github.lovexiaopeng.jimu.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Same original JAR, bounded existing owner fixture, private MySQL and full-table restoration. */
class QueryViewAssemblyIT {
  @Test
  void publicQueriesShareConnectionAndRestoreAfterPermissionRefusal() throws Exception {
    assertTrue(System.getenv("JIMU_IT_JAVA") != null, "Reviewed Linux Java21 required");
    Path folder = Path.of("target/failsafe-reports/query-view");
    Files.createDirectories(folder);
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
            linux(Path.of("tools/xiao60_query_view_verify.py")),
            "--jar",
            linux(Path.of(System.getProperty("jimu.jar"))),
            "--old-jar",
            linux(Path.of("target/xiao60/old/b2-d23a89e.jar")),
            "--evidence",
            linux(folder)));
    var child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(folder.resolve("verify.log").toFile())
            .start();
    try {
      assertTrue(child.waitFor(8, TimeUnit.MINUTES), "Bounded query-view private comparison");
      assertEquals(0, child.exitValue(), "See query-view/verify.log");
      String cleanup = Files.readString(folder.resolve("cleanup.json"));
      assertTrue(
          cleanup.contains("\"allChildrenExited\": true")
              && cleanup.contains("\"containerRemoved\": true"));
    } finally {
      if (child.isAlive()) {
        child.destroy();
        if (!child.waitFor(20, TimeUnit.SECONDS)) child.destroyForcibly();
        child.waitFor();
      }
    }
  }

  private static String linux(Path path) {
    String full = path.toAbsolutePath().toString().replace('\\', '/');
    return System.getProperty("os.name").startsWith("Windows")
        ? "/mnt/" + Character.toLowerCase(full.charAt(0)) + full.substring(2)
        : full;
  }
}
