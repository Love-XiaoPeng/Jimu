package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.ProcessIdentity;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One bounded native operation, under the worker's existing operation ownership. */
public final class NativeRunner {
  public record Bundle(String launcher, String launcherSha256, String rg, String bubblewrap) {}

  public record Output(byte[] stdout, String stderr, int exit, NativeIdentity identity) {}

  private static final String RG_HASH =
      "193906679498de4d939345b937fa24e0e69a03c244bd70c859f5e41232713f21";
  private static final String BWRAP_HASH =
      "e318903862396f96de3df57264e0158682b952fd3fb53ac23d876413e7b30f71";
  private final Bundle bundle;
  private final OperationThreads operations;
  private final AtomicBoolean busy = new AtomicBoolean();

  public NativeRunner(Bundle bundle, OperationThreads operations) throws IOException {
    this.bundle = bundle;
    this.operations = operations;
    verifyBundle();
  }

  private void verifyBundle() throws IOException {
    if (((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).intValue() == 0)
      throw new IOException("Native search requires a non-root worker");
    verify(Path.of(bundle.launcher()), bundle.launcherSha256());
    verify(Path.of(bundle.rg()), RG_HASH);
    verify(Path.of(bundle.bubblewrap()), BWRAP_HASH);
  }

  private static void verify(Path file, String hash) throws IOException {
    try (var directory = SearchFiles.open(file.toAbsolutePath().getParent())) {
      var attributes =
          directory
              .getFileAttributeView(
                  file.getFileName(),
                  java.nio.file.attribute.BasicFileAttributeView.class,
                  LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
      int mode =
          ((Number) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
      if (!attributes.isRegularFile()
          || attributes.size() > 32 * 1024 * 1024
          || (mode & 06022) != 0
          || !Files.isExecutable(file)
          || !Payloads.hash(
                  SearchFiles.read(
                      file.getParent(), file.getFileName().toString(), 32 * 1024 * 1024))
              .equals(hash)) throw new IOException("Native bundle identity mismatch");
    }
  }

  private void spec(Path directory, Path projection, NativeSearchQuery query, String effect)
      throws IOException {
    var buffer = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(buffer)) {
      var projectionId = SearchFiles.identity(projection);
      var rgId = SearchFiles.identity(Path.of(bundle.rg()));
      var bwrapId = SearchFiles.identity(Path.of(bundle.bubblewrap()));
      for (String field :
          List.of(
              "JIMU_SEARCH_V1",
              effect,
              projection.toString(),
              projectionId[0],
              projectionId[1],
              bundle.rg(),
              rgId[0],
              rgId[1],
              bundle.bubblewrap(),
              bwrapId[0],
              bwrapId[1],
              query.tool(),
              query.pattern(),
              query.path(),
              query.include() == null ? "" : query.include())) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
      }
    }
    SearchFiles.write(directory, "spec", buffer.toByteArray());
  }

  public Output run(
      Path directory,
      Path projection,
      NativeSearchQuery query,
      String effect,
      Runnable checkpoint,
      Consumer<NativeIdentity> started,
      Consumer<NativeIdentity> stopped) {
    if (!busy.compareAndSet(false, true)) throw new FileFailure("SEARCH_PROCESS_UNPROVEN");
    operations.nativeStarted();
    boolean proven = false;
    Process process = null;
    NativeIdentity identity = null;
    Drain stdout = null, stderr = null;
    AtomicReference<String> overflow = new AtomicReference<>();
    RuntimeException failure = null;
    try {
      checkpoint.run();
      verifyBundle();
      spec(directory, projection, query, effect);
      String[] inode = SearchFiles.identity(directory);
      process =
          new ProcessBuilder(bundle.launcher(), directory.toString(), inode[0], inode[1]).start();
      ProcessIdentity launcher = NativeProcesses.identity(process.pid());
      identity = new NativeIdentity(launcher, null, null);
      stdout = drain(process.getInputStream(), 20000000, false, overflow);
      stderr = drain(process.getErrorStream(), 65536, true, overflow);
      process.getOutputStream().write('S');
      process.getOutputStream().flush();
      long startup = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (true) {
        checkpoint.run();
        try {
          identity = NativeProcesses.started(directory, effect).identity();
          break;
        } catch (NoSuchFileException pending) {
          if (!process.isAlive() || System.nanoTime() > startup)
            throw new FileFailure("SEARCH_ISOLATION_UNAVAILABLE");
          Thread.sleep(10);
        }
      }
      started.accept(identity);
      checkpoint.run();
      process.getOutputStream().write('G');
      process.getOutputStream().flush();
      while (!process.waitFor(20, TimeUnit.MILLISECONDS)) {
        checkpoint.run();
        if (overflow.get() != null) throw new FileFailure(overflow.get());
      }
      byte[] raw = stdout.await(), errors = stderr.await();
      if (overflow.get() != null) throw new FileFailure(overflow.get());
      if (!NativeProcesses.stopped(identity)) throw new FileFailure("SEARCH_PROCESS_UNPROVEN");
      stopped.accept(identity);
      proven = true;
      return new Output(
          raw, new String(errors, StandardCharsets.UTF_8), process.exitValue(), identity);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new FileFailure("SEARCH_PROCESS_UNPROVEN", interrupted);
    } catch (IOException error) {
      throw new FileFailure("SEARCH_FAILED", error);
    } catch (RuntimeException error) {
      failure = error;
      throw error;
    } finally {
      if (!proven) {
        boolean interrupted = Thread.interrupted();
        try {
          if (process == null) {
            stopped.accept(new NativeIdentity(null, null, null));
            proven = true;
          } else {
            try {
              process.getOutputStream().close();
            } catch (IOException closedGate) {
              // A launcher that rejected startup can close its gate first. Still prove death
              // from the durable receipts and proc identities below.
            }
            process.destroy();
            if (!process.waitFor(3100, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            process.waitFor(1000, TimeUnit.MILLISECONDS);
            // Durable startup identity wins even if failure happened before Java's DB update.
            try {
              identity = NativeProcesses.started(directory, effect).identity();
            } catch (NoSuchFileException absent) {
              // A launch receipt without a namespace receipt is an unproven launch window.
              try {
                NativeProcesses.launch(directory, effect);
                identity = null;
              } catch (NoSuchFileException neverLaunched) {
                // The launcher has exited and C creates no child before durable launch.json.
                if (!process.isAlive()) identity = new NativeIdentity(null, null, null);
              }
            }
            if (identity != null) {
              NativeProcesses.signal(identity, true);
              long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
              while (!NativeProcesses.stopped(identity) && System.nanoTime() < end)
                Thread.sleep(10);
              if (!process.isAlive() && NativeProcesses.stopped(identity)) {
                if (stdout != null) stdout.thread().join();
                if (stderr != null) stderr.thread().join();
                stopped.accept(identity);
                proven = true;
              }
            }
          }
        } catch (IOException | InterruptedException cleanup) {
          if (cleanup instanceof InterruptedException) interrupted = true;
          // Keep the native slot and durable PREPARED/STARTED record for the same supervisor.
          System.getLogger(NativeRunner.class.getName())
              .log(System.Logger.Level.WARNING, "Native stop unproven");
        } finally {
          if (interrupted) Thread.currentThread().interrupt();
        }
      }
      if (proven) {
        operations.nativeStopped();
        busy.set(false);
      } else if (!(failure instanceof io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure))
        throw new FileFailure("SEARCH_PROCESS_UNPROVEN");
    }
  }

  private record Drain(CompletableFuture<byte[]> result, Thread thread) {
    byte[] await() throws InterruptedException {
      thread.join();
      return result.join();
    }
  }

  private Drain drain(
      InputStream stream, int bound, boolean tail, AtomicReference<String> overflow) {
    var result = new CompletableFuture<byte[]>();
    Thread thread =
        operations.start(
            "native-search-drain",
            () -> {
              try (stream;
                  var out = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192], ring = tail ? new byte[bound] : null;
                int count, position = 0;
                long total = 0;
                while ((count = stream.read(block)) != -1) {
                  if (tail) {
                    for (int i = 0; i < count; i++) {
                      ring[position] = block[i];
                      position = (position + 1) % bound;
                    }
                    total += count;
                  } else if ((long) out.size() + count > bound) {
                    overflow.compareAndSet(null, "SEARCH_RAW_OUTPUT_OVERFLOW");
                    break;
                  } else out.write(block, 0, count);
                }
                if (tail) {
                  int size = (int) Math.min(total, bound);
                  for (int i = 0; i < size; i++)
                    out.write(ring[Math.floorMod(position - size + i, bound)]);
                }
                result.complete(out.toByteArray());
              } catch (IOException | RuntimeException error) {
                result.completeExceptionally(error);
              }
            });
    return new Drain(result, thread);
  }
}
