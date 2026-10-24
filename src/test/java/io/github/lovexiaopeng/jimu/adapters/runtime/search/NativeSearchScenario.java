package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Product C launcher + Java adapter on actual non-root Linux; no provider substitute for search.
 */
public final class NativeSearchScenario {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    var operations = new OperationThreads();
    var bundle = new NativeRunner.Bundle(args[1], args[2], args[3], args[4]);
    if (args.length > 5) {
      NativeCases.run(root, bundle, operations, args[5], Path.of(args[6]));
      return;
    }
    var runtime = new NativeSearchRuntime(root, bundle, operations);
    if (!runtime.available()) throw new AssertionError("Native readiness");
    var runner = new NativeRunner(bundle, operations);
    String text = "alpha\n积木🙂\nalpha alpha\n";
    var snapshot =
        new SecureWorkspaceStore.WorkspaceSnapshot(
            "s",
            Map.of(
                "src/a.txt",
                    new SecureWorkspaceStore.FileValue(
                        1,
                        text,
                        Payloads.hash(text),
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        1700000000000000000L),
                ".hidden",
                    new SecureWorkspaceStore.FileValue(
                        1, "alpha", Payloads.hash("alpha"), 5, 1700000000000000001L)));
    for (var query :
        java.util.List.of(
            new NativeSearchQuery("glob", "*", ".", null),
            new NativeSearchQuery("grep", "alpha|积木", ".", null),
            new NativeSearchQuery("grep", "[", ".", null))) {
      Path directory =
          Files.createTempDirectory(
              root,
              "search-",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      Path projection =
          Files.createDirectory(
              directory.resolve("projection"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      SearchFiles.project(projection, snapshot, () -> {});
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      var output =
          runner.run(
              directory,
              projection,
              query,
              UUID.randomUUID().toString(),
              () -> {
                if (System.nanoTime() > end) throw new AssertionError("Native deadline");
              },
              identity -> System.out.println("STARTED " + Payloads.json(identity)),
              identity -> System.out.println("STOPPED " + Payloads.json(identity)));
      System.out.println("EXIT " + output.exit() + " STDERR " + output.stderr());
      System.out.println(new String(output.stdout(), java.nio.charset.StandardCharsets.UTF_8));
      if (query.pattern().equals("[")) {
        if (output.exit() != 2) throw new AssertionError("Invalid regex exit");
      } else {
        if (output.exit() != 0) throw new AssertionError("Native search exit");
        var result =
            NativeResults.collect(
                query, output.stdout(), snapshot.files().keySet(), "s", "q", "pin");
        System.out.println(Payloads.json(result));
        if (query.tool().equals("glob") && !result.pages().getFirst().contains(".hidden"))
          throw new AssertionError("Hidden glob");
        if (query.tool().equals("grep") && !result.pages().getFirst().contains("\"seen\":3"))
          throw new AssertionError("Matched lines");
      }
      SearchFiles.removeTree(directory);
    }
    operations.awaitDrained();
    System.out.println("PASS non-root product native smoke");
  }
}
