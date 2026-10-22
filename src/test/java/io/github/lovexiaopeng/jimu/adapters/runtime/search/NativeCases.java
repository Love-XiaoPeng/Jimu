package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SearchPort;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Actual product launcher/rg cases. Expected file bytes and membership are fixture oracles. */
final class NativeCases {
  private final Path root, evidence;
  private final NativeRunner.Bundle bundle;
  private final OperationThreads operations;
  private final NativeRunner runner;
  private final List<NativeIdentity> identities = new ArrayList<>();
  private int sequence;

  private NativeCases(
      Path root, NativeRunner.Bundle bundle, OperationThreads operations, Path evidence)
      throws Exception {
    this.root = root;
    this.bundle = bundle;
    this.operations = operations;
    this.evidence = evidence;
    new NativeSearchRuntime(root, bundle, operations);
    runner = new NativeRunner(bundle, operations);
  }

  static void run(
      Path root,
      NativeRunner.Bundle bundle,
      OperationThreads operations,
      String selected,
      Path evidence)
      throws Exception {
    var cases = new NativeCases(root, bundle, operations, evidence);
    switch (selected) {
      case "S01" -> cases.glob();
      case "S02" -> cases.grep();
      case "S03" -> cases.escape();
      case "S06" -> cases.bounds();
      default -> throw new IllegalArgumentException("Unknown native case");
    }
    operations.awaitDrained();
    for (var identity : cases.identities)
      check(cases.runner.stopped(identity), "all recorded identities remain stopped");
    Files.writeString(
        evidence.resolve(selected + "-identities.json"), Payloads.json(cases.identities));
    check(operations.activeCount() == 0, "no live operation or drainer");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    System.out.println("PASS " + message);
  }

  private static SecureWorkspaceStore.WorkspaceSnapshot snapshot(Map<String, String> files) {
    var values = new LinkedHashMap<String, SecureWorkspaceStore.FileValue>();
    long mtime = 1700000000000000000L;
    for (var entry : files.entrySet())
      values.put(
          entry.getKey(),
          new SecureWorkspaceStore.FileValue(
              1,
              entry.getValue(),
              Payloads.hash(entry.getValue()),
              entry.getValue().getBytes(StandardCharsets.UTF_8).length,
              mtime++));
    return new SecureWorkspaceStore.WorkspaceSnapshot(Payloads.hash(Payloads.json(values)), values);
  }

  private Path directory() throws Exception {
    return Files.createTempDirectory(
        root,
        "run-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private Path projection(Path directory) throws Exception {
    return Files.createDirectory(
        directory.resolve("projection"),
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }

  private NativeRunner.Output execute(Path directory, Path projection, NativeSearchQuery query)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    var output =
        runner.run(
            directory,
            projection,
            query,
            UUID.randomUUID().toString(),
            () -> {
              if (System.nanoTime() > deadline) throw new FileFailure("SEARCH_ABORTED");
            },
            identity -> {
              identities.add(identity);
              try {
                check(
                    !identity
                        .init()
                        .pidNamespace()
                        .equals(
                            NativeProcesses.identity(ProcessHandle.current().pid()).pidNamespace()),
                    "separate PID namespace before gate");
                Files.writeString(
                    evidence.resolve("argv-" + sequence + ".txt"),
                    Files.readString(
                            Path.of("/proc", Long.toString(identity.bubblewrap().pid()), "cmdline"))
                        .replace('\0', '\n'));
              } catch (Exception error) {
                throw new IllegalStateException(error);
              }
            },
            identity -> {
              try {
                check(
                    runner.stopped(identity), "actual launcher/bwrap/init stopped before callback");
              } catch (Exception error) {
                throw new IllegalStateException(error);
              }
            });
    Files.write(evidence.resolve("stdout-" + sequence + ".txt"), output.stdout());
    Files.writeString(
        evidence.resolve("exit-" + sequence++ + ".json"),
        Payloads.json(Map.of("exit", output.exit(), "stderr", output.stderr())));
    return output;
  }

  private NativeResults.Document search(
      Map<String, String> files, NativeSearchQuery query, int exit) throws Exception {
    var snapshot = snapshot(files);
    Path directory = directory(), projection = projection(directory);
    SearchFiles.project(projection, snapshot, () -> {});
    var output = execute(directory, projection, query);
    check(output.exit() == exit, "native exit " + exit + " for " + query.tool());
    var result =
        exit < 2
            ? NativeResults.collect(
                query,
                output.stdout(),
                snapshot.files().keySet(),
                snapshot.digest(),
                "query",
                "pin")
            : null;
    if (result != null)
      Files.writeString(evidence.resolve("pages-" + sequence + ".json"), Payloads.json(result));
    SearchFiles.removeTree(directory);
    return result;
  }

  private void glob() throws Exception {
    var files = new LinkedHashMap<String, String>();
    for (int i = 0; i < 110; i++) files.put("deep/f" + i + ".txt", "x");
    for (String path :
        List.of(
            "other/last.txt", "-dash.txt", ".hidden.txt", "ignored.txt", "src/a.js", "src/b.jsx"))
      files.put(path, "x");
    files.put(".gitignore", "ignored.txt\n");
    files.put(".git/config", "fixture");
    for (String vcs : List.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl"))
      files.put(vcs + "/hidden.txt", "secret");
    var result = search(files, new NativeSearchQuery("glob", "*.txt", null, null), 0);
    var all = new ArrayList<String>();
    for (String json : result.pages())
      all.addAll(Payloads.read(json, SearchPort.GlobPage.class).paths());
    var expected = new HashSet<String>();
    for (String path : files.keySet())
      if (path.endsWith(".txt") && !path.matches("\\.(git|svn|hg|bzr|jj|sl)/.*"))
        expected.add(path);
    check(
        all.size() == expected.size() && new HashSet<>(all).equals(expected),
        "native pagination covers exact oracle files without duplicates");
    var first = Payloads.read(result.pages().getFirst(), SearchPort.GlobPage.class);
    check(
        first.returned() == 100
            && first
                .paths()
                .containsAll(List.of("other/last.txt", "-dash.txt", ".hidden.txt", "ignored.txt")),
        "first page samples all top-level groups");
    check(
        all.subList(100, all.size())
            .equals(
                new ArrayList<>(
                    files.keySet().stream()
                        .filter(expected::contains)
                        .filter(p -> !first.paths().contains(p))
                        .toList())),
        "remainder preserves actual nanosecond mtime order");
    var scoped = search(files, new NativeSearchQuery("glob", "*.{js,jsx}", "src", null), 0);
    check(
        Payloads.read(scoped.pages().getFirst(), SearchPort.GlobPage.class)
            .paths()
            .equals(List.of("src/a.js", "src/b.jsx")),
        "brace and logical root retain original paths and mtime");
  }

  private void grep() throws Exception {
    var files = new LinkedHashMap<String, String>();
    files.put("src/a.js", "alpha alpha\r\n中文🙂\r\n \n");
    files.put("src/b.jsx", "alpha\n");
    files.put(".hidden", "alpha");
    files.put("ignored.txt", "alpha");
    files.put(".gitignore", "ignored.txt\n");
    files.put(".git/config", "");
    var result = search(files, new NativeSearchQuery("grep", "alpha|中文", null, "*.{js,jsx}"), 0);
    var page = Payloads.read(result.pages().getFirst(), SearchPort.GrepPage.class);
    check(
        page.seen() == 3
            && page.matches().stream()
                    .filter(m -> m.line().equals("alpha alpha") && m.lineNumber() == 1)
                    .count()
                == 1,
        "grep counts matching lines, preserves CRLF and multiple submatches");
    check(
        page.matches().stream().anyMatch(m -> m.line().equals("中文🙂") && m.lineNumber() == 2),
        "native Rust regex preserves Unicode and 1-based line numbers");
    var defaults =
        Payloads.read(
            search(files, new NativeSearchQuery("grep", "alpha", null, null), 0).pages().getFirst(),
            SearchPort.GrepPage.class);
    check(
        defaults.seen() == 2
            && defaults.matches().stream()
                .noneMatch(m -> m.path().equals("ignored.txt") || m.path().equals(".hidden")),
        "grep default hidden and git ignore rules differ from glob");
    check(
        Payloads.read(
                    search(files, new NativeSearchQuery("grep", " ", "src/a.js", null), 0)
                        .pages()
                        .getFirst(),
                    SearchPort.GrepPage.class)
                .seen()
            == 2,
        "whitespace regex is valid");
    check(
        Payloads.read(
                    search(files, new NativeSearchQuery("grep", "absent", null, null), 1)
                        .pages()
                        .getFirst(),
                    SearchPort.GrepPage.class)
                .seen()
            == 0,
        "exit1 is complete empty result");
    search(files, new NativeSearchQuery("grep", "[", null, null), 2);
    search(files, new NativeSearchQuery("grep", "(?<=alpha) alpha", null, null), 2);
  }

  private void escape() throws Exception {
    var self = NativeProcesses.identity(ProcessHandle.current().pid());
    var stale =
        new io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.ProcessIdentity(
            self.pid(), self.startTicks() + 1, self.bootId(), self.pidNamespace());
    check(NativeProcesses.stopped(stale), "PID reuse receipt cannot match live process");
    NativeProcesses.signal(stale, true);
    check(!NativeProcesses.stopped(self), "stale receipt never signals reused live PID");
    Path ancestor = directory(), child = projection(ancestor);
    Path alias = root.resolve("ancestor-link");
    Files.createSymbolicLink(alias, ancestor);
    try {
      SearchFiles.open(alias.resolve("projection")).close();
      throw new AssertionError("ancestor symlink accepted");
    } catch (java.io.IOException rejected) {
      check(true, "search reopens reject ancestor symlink");
    }
    try {
      new SecureWorkspaceStore(alias.resolve("projection"));
      throw new AssertionError("state-root ancestor symlink accepted");
    } catch (java.io.IOException rejected) {
      check(true, "workspace state root rejects ancestor symlink");
    }
    Files.delete(alias);
    SearchFiles.removeTree(ancestor);
    String sentinel = "HOST_SENTINEL_" + UUID.randomUUID();
    Path host = root.resolve("host");
    Files.writeString(host, sentinel);
    Path sibling = root.resolve("sibling");
    Files.createDirectory(sibling);
    Files.writeString(sibling.resolve("secret"), sentinel);
    for (String destination :
        List.of(
            host.toString(),
            sibling.resolve("secret").toString(),
            "/proc/self/fd/3/../sibling/secret",
            "/etc/passwd")) {
      Path directory = directory(), projection = projection(directory);
      Files.createSymbolicLink(projection.resolve("leak"), Path.of(destination));
      var output =
          execute(directory, projection, new NativeSearchQuery("grep", sentinel, "leak", null));
      check(
          output.exit() == 2
              && !new String(output.stdout(), StandardCharsets.UTF_8).contains(sentinel),
          "explicit host/sibling/proc FD symlink cannot escape empty namespace");
      SearchFiles.removeTree(directory);
    }
    Path directory = directory(), projection = projection(directory);
    SearchFiles.write(projection, "a", "safe".getBytes(StandardCharsets.UTF_8));
    Path held = directory.resolve("held");
    Files.move(projection, held);
    Files.createSymbolicLink(projection, held);
    try {
      execute(directory, projection, new NativeSearchQuery("grep", "safe", null, null));
      throw new AssertionError("symlink projection accepted");
    } catch (FileFailure error) {
      check(
          error.code().equals("SEARCH_FAILED")
              || error.code().equals("SEARCH_ISOLATION_UNAVAILABLE"),
          "launcher rejects final projection symlink");
    }
    SearchFiles.removeTree(directory);
    Path conflict = directory(), tree = projection(conflict);
    try {
      SearchFiles.project(tree, snapshot(Map.of("a", "one", "a/b", "two")), () -> {});
      throw new AssertionError("prefix conflict");
    } catch (FileFailure error) {
      check(
          error.code().equals("SEARCH_WORKSPACE_UNREPRESENTABLE"),
          "prefix conflict fails before any native launch");
    }
    SearchFiles.removeTree(conflict);
    pinnedRename();
    Files.delete(host);
    SearchFiles.removeTree(sibling);
  }

  private void pinnedRename() throws Exception {
    Path directory = directory(), projection = projection(directory);
    SearchFiles.write(projection, "a", "ORIGINAL_INODE".getBytes(StandardCharsets.UTF_8));
    var fields = new ArrayList<>(List.of("JIMU_SEARCH_V1", UUID.randomUUID().toString()));
    for (Path path : List.of(projection, Path.of(bundle.rg()), Path.of(bundle.bubblewrap()))) {
      fields.add(path.toString());
      fields.addAll(List.of(SearchFiles.identity(path)));
    }
    fields.addAll(List.of("grep", "ORIGINAL_INODE|REPLACEMENT", ".", ""));
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      for (String field : fields) {
        byte[] raw = field.getBytes(StandardCharsets.UTF_8);
        out.writeInt(raw.length);
        out.write(raw);
      }
    }
    SearchFiles.write(directory, "spec", bytes.toByteArray());
    String[] inode = SearchFiles.identity(directory);
    Path stdout = evidence.resolve("pinned-rename.out"),
        stderr = evidence.resolve("pinned-rename.err");
    Process child =
        new ProcessBuilder(bundle.launcher(), directory.toString(), inode[0], inode[1])
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
    NativeIdentity identity = null;
    try {
      await(directory.resolve("launch.json"), child);
      Path original = directory.resolve("original");
      Files.move(projection, original);
      Files.createDirectory(projection);
      Files.writeString(projection.resolve("a"), "REPLACEMENT");
      child.getOutputStream().write('S');
      child.getOutputStream().flush();
      await(directory.resolve("started.json"), child);
      identity = NativeProcesses.started(directory, fields.get(1)).identity();
      identities.add(identity);
      child.getOutputStream().write('G');
      child.getOutputStream().flush();
      check(
          child.waitFor(10, TimeUnit.SECONDS) && child.exitValue() == 0,
          "pinned rename launcher exits");
      String raw = Files.readString(stdout);
      check(
          raw.contains("ORIGINAL_INODE") && !raw.contains("REPLACEMENT"),
          "open FD still searches original inode after path rename/replacement");
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor();
      }
      if (identity != null) {
        runner.signal(identity, true);
        check(runner.stopped(identity), "pinned rename namespace stopped");
      }
      SearchFiles.removeTree(directory);
    }
  }

  private static void await(Path receipt, Process child) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
    while (!Files.exists(receipt)) {
      if (!child.isAlive() || System.nanoTime() > end)
        throw new AssertionError("missing startup receipt " + receipt.getFileName());
      Thread.sleep(10);
    }
  }

  private void bounds() throws Exception {
    search(Map.of(), new NativeSearchQuery("glob", "*", null, null), 1);
    search(Map.of("empty", ""), new NativeSearchQuery("grep", "x", null, null), 1);
    var result =
        search(
            Map.of("a", "中".repeat(1000) + "🙂\r\n"),
            new NativeSearchQuery("grep", "中", null, null),
            0);
    var match =
        Payloads.read(result.pages().getFirst(), SearchPort.GrepPage.class).matches().getFirst();
    check(
        match.line().equals("中".repeat(666) + "… [truncated]") && match.lineTruncated(),
        "real rg long Unicode line has exact UTF8 preview");
    Path directory = directory(), projection = projection(directory);
    String large = "x\n".repeat(200000);
    SearchFiles.write(projection, "flood", large.getBytes(StandardCharsets.UTF_8));
    try {
      execute(directory, projection, new NativeSearchQuery("grep", "x", null, null));
      throw new AssertionError("stdout overflow accepted");
    } catch (FileFailure error) {
      check(
          error.code().equals("SEARCH_RAW_OUTPUT_OVERFLOW"),
          "actual rg stdout flood is a failure, never partial success");
    }
    check(operations.activeCount() == 0, "flood process and both drainers collected");
    SearchFiles.removeTree(directory);
    var excessive = new LinkedHashMap<String, String>();
    for (int i = 0; i < 4097; i++) excessive.put("f" + i, "");
    directory = directory();
    projection = projection(directory);
    try {
      SearchFiles.project(projection, snapshot(excessive), () -> {});
      throw new AssertionError("file count accepted");
    } catch (FileFailure error) {
      check(
          error.code().equals("SEARCH_WORKSPACE_LIMIT"),
          "4097 ordinary projection files rejected before launch");
    }
    SearchFiles.removeTree(directory);
  }
}
