package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.BoundaryProbe;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.adapters.storage.SecureWorkspaceStore;
import io.github.lovexiaopeng.jimu.kernel.search.api.NativeResultPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.search.api.NativeSearchPhase;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.Run;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import io.github.lovexiaopeng.jimu.plugin.api.SearchErrorCode;
import io.github.lovexiaopeng.jimu.plugin.api.SearchPort;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Host search binding. No model-supplied tenant, operation identity, native path or argv. */
public final class NativeSearchRuntime {
  private final Path root;
  private final NativeRunner runner;

  public NativeSearchRuntime() {
    root = null;
    runner = null;
  }

  public NativeSearchRuntime(Path root, NativeRunner.Bundle bundle, OperationThreads operations)
      throws IOException {
    this.root = root.toAbsolutePath().normalize();
    SearchFiles.privateRoot(this.root);
    runner = new NativeRunner(bundle, operations);
    // Readiness exercises the same launcher, namespace, FD mounts and independent gate, with no
    // task data.
    Path test =
        Files.createTempDirectory(
            this.root,
            "readiness-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    boolean stopped = false;
    try {
      Path projection =
          Files.createDirectory(
              test.resolve("projection"),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      var output =
          runner.run(
              test,
              projection,
              new NativeSearchQuery(ToolCapability.GLOB, "*", ".", null),
              UUID.randomUUID().toString(),
              () -> {
                if (System.nanoTime() > end)
                  throw new FileFailure(SearchErrorCode.SEARCH_ISOLATION_UNAVAILABLE);
              },
              identity -> {},
              identity -> {});
      stopped = true;
      if (output.exit() != 0 && output.exit() != 1)
        throw new IOException("Native isolation self-test failed");
      if (output.stdout().length != 0) throw new IOException("Nonempty native self-test");
    } finally {
      if (stopped) SearchFiles.removeTree(test);
    }
  }

  public boolean available() {
    return runner != null;
  }

  public boolean absent(long pid, String bootId, String observerPidNamespace) throws IOException {
    return runner != null && runner.absent(pid, bootId, observerPidNamespace);
  }

  /** Disabled or unprovable recovery keeps the durable run and its storage reservation. */
  public boolean reconcile(NativeIdentity identity, boolean stop, boolean force)
      throws IOException {
    if (runner == null) return false;
    if (stop) runner.signal(identity, force);
    return runner.stopped(identity);
  }

  public void requireAvailable() {
    if (!available()) throw new TaskFailure(503, SearchErrorCode.SEARCH_NOT_CONFIGURED);
  }

  public SearchPort bind(
      SecureWorkspaceStore workspace,
      ExecutionLedger ledger,
      NativeSearchLedger searches,
      ExecutionLedger.Lease lease,
      ExecutionLedger.Invocation invocation,
      String pin,
      BoundaryProbe probe) {
    requireAvailable();
    return new SearchPort() {
      @Override
      public GlobPage glob(String pattern, String path, String cursor) {
        return Payloads.read(
            execute(new NativeSearchQuery(ToolCapability.GLOB, pattern, path, null), cursor),
            GlobPage.class);
      }

      @Override
      public GrepPage grep(String pattern, String path, String include, String cursor) {
        return Payloads.read(
            execute(new NativeSearchQuery(ToolCapability.GREP, pattern, path, include), cursor),
            GrepPage.class);
      }

      private String execute(NativeSearchQuery query, String cursor) {
        NativeSearchQuery.cursor(cursor);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        Runnable checkpoint =
            () -> {
              ledger.checkpoint(lease);
              if (System.nanoTime() > end || Instant.now().isAfter(lease.deadline()))
                throw new TaskFailure(409, TaskErrorCode.DEADLINE_EXCEEDED);
            };
        checkpoint.run();
        String queryDigest = Payloads.hash(Payloads.json(query));
        if (cursor != null) {
          var stored = searches.searchPage(lease, queryDigest, pin, cursor);
          try {
            byte[] bytes =
                SearchFiles.read(
                    Path.of(stored.directory()),
                    "result.json",
                    NativeResultPolicyV1.DOCUMENT_BYTES);
            if (bytes.length != stored.size() || !Payloads.hash(bytes).equals(stored.hash()))
              throw new FileFailure(TaskErrorCode.SNAPSHOT_CORRUPT);
            var document =
                Payloads.read(
                    new String(bytes, StandardCharsets.UTF_8), NativeResults.Document.class);
            if (!document.query().equals(queryDigest)
                || !document.pin().equals(pin)
                || !Integer.valueOf(stored.page()).equals(document.cursors().get(cursor))
                || stored.page() < 0
                || stored.page() >= document.pages().size())
              throw new FileFailure(TaskErrorCode.SNAPSHOT_CORRUPT);
            checkpoint.run();
            return document.pages().get(stored.page());
          } catch (IOException error) {
            throw new FileFailure(SearchErrorCode.SEARCH_FAILED, error);
          }
        }
        var snapshot = workspace.searchSnapshot(lease.tenant(), lease.taskId(), checkpoint::run);
        Path directory = root.resolve(invocation.effectId());
        Path projection = directory.resolve("projection");
        Run run =
            new Run(
                lease.tenant(),
                lease.taskId(),
                invocation.id(),
                invocation.effectId(),
                lease.attemptId(),
                lease.worker(),
                directory.toString(),
                snapshot.digest(),
                NativeSearchPhase.PREPARED,
                null,
                false);
        AtomicBoolean stopped = new AtomicBoolean();
        boolean prepared = false,
            created = false,
            launched = false,
            completed = false,
            settling = false;
        try {
          Files.createDirectory(
              directory,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
          created = true;
          searches.prepareSearch(
              lease, invocation, directory.toString(), snapshot.digest(), queryDigest, pin);
          prepared = true;
          Files.createDirectory(
              projection,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
          probe.hit("S_SNAPSHOT", lease.taskId());
          SearchFiles.project(projection, snapshot, checkpoint);
          probe.hit("S_BEFORE_LAUNCH", lease.taskId());
          launched = true;
          var output =
              runner.run(
                  directory,
                  projection,
                  query,
                  invocation.effectId(),
                  checkpoint,
                  identity -> {
                    probe.hit("S_IDENTITY", lease.taskId());
                    searches.searchStarted(lease, invocation.id(), identity);
                    probe.hit("S_GATE", lease.taskId());
                  },
                  identity -> {
                    searches.searchStopped(run, identity);
                    stopped.set(true);
                  });
          checkpoint.run();
          if (output.exit() != 0 && output.exit() != 1) {
            String stderr = output.stderr();
            throw new FileFailure(
                stderr.contains("regex parse error") || stderr.contains("error parsing glob")
                    ? SearchErrorCode.SEARCH_INVALID_PATTERN
                    : SearchErrorCode.SEARCH_FAILED);
          }
          var result =
              NativeResults.collect(
                  query,
                  output.stdout(),
                  snapshot.files().keySet(),
                  snapshot.digest(),
                  queryDigest,
                  pin);
          byte[] bytes = Payloads.json(result).getBytes(StandardCharsets.UTF_8);
          SearchFiles.write(directory, "result.json", bytes);
          checkpoint.run();
          SearchFiles.removeTree(projection);
          checkpoint.run();
          probe.hit("S_BEFORE_RESULT", lease.taskId());
          settling = true;
          searches.searchCompleted(
              lease, invocation.id(), Payloads.hash(bytes), bytes.length, result.cursors());
          completed = true;
          return result.pages().getFirst();
        } catch (IOException error) {
          throw new FileFailure(SearchErrorCode.SEARCH_FAILED, error);
        } finally {
          // Only proven native exit permits deleting the export. Unproven windows remain for
          // supervisor.
          if (prepared && !launched) {
            searches.searchStopped(run, new NativeIdentity(null, null, null));
            stopped.set(true);
          }
          if (prepared && stopped.get() && !completed && !settling) {
            try {
              SearchFiles.removeTree(directory);
              searches.searchStorageRemoved(run);
            } catch (IOException error) {
              throw new FileFailure(SearchErrorCode.SEARCH_FAILED, error);
            }
          } else if (!prepared && created) {
            try {
              SearchFiles.removeTree(directory);
            } catch (IOException error) {
              throw new FileFailure(SearchErrorCode.SEARCH_FAILED, error);
            }
          } else if (stopped.get()
              && Files.exists(projection, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
              SearchFiles.removeTree(projection);
            } catch (IOException error) {
              throw new FileFailure(SearchErrorCode.SEARCH_FAILED, error);
            }
          }
        }
      }
    };
  }
}
