package io.github.lovexiaopeng.jimu.adapters.runtime.search;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.NativeIdentity;
import io.github.lovexiaopeng.jimu.kernel.task.api.NativeSearchLedger.ProcessIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Physical observations. Unreadable proc/receipts never imply process death. */
public final class NativeProcesses {
  public record Launch(String effect, ProcessIdentity launcher) {}

  public record Started(String effect, NativeIdentity identity) {}

  private NativeProcesses() {}

  public static ProcessIdentity identity(long pid) throws IOException {
    String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
    String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
    String ns =
        Files.getAttribute(Path.of("/proc", Long.toString(pid), "ns/pid"), "unix:ino").toString();
    return new ProcessIdentity(
        pid,
        Long.parseLong(fields[19]),
        Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip(),
        ns);
  }

  public static boolean stopped(ProcessIdentity expected) throws IOException {
    if (expected == null) return true;
    if (!Files.readSymbolicLink(Path.of("/proc/self"))
        .toString()
        .equals(Long.toString(ProcessHandle.current().pid())))
      throw new IOException("Proc PID namespace does not match observer");
    if (!expected
        .bootId()
        .equals(Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).strip())) return true;
    String stat = Files.readString(Path.of("/proc", Long.toString(expected.pid()), "stat"));
    String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
    if (Long.parseLong(fields[19]) != expected.startTicks()) return true;
    if (fields[0].equals("Z") || fields[0].equals("X")) return true;
    if (!Files.getAttribute(Path.of("/proc", Long.toString(expected.pid()), "ns/pid"), "unix:ino")
        .toString()
        .equals(expected.pidNamespace()))
      throw new IOException("Native process namespace identity mismatch");
    return false;
  }

  public static boolean stopped(NativeIdentity identity) throws IOException {
    return stopped(identity.launcher())
        && stopped(identity.bubblewrap())
        && stopped(identity.init());
  }

  public static Started started(Path directory, String effect) throws IOException {
    Started receipt =
        Payloads.read(
            new String(SearchFiles.read(directory, "started.json", 4096), StandardCharsets.UTF_8),
            Started.class);
    if (!effect.equals(receipt.effect())) throw new IOException("Native receipt scope mismatch");
    return receipt;
  }

  public static Launch launch(Path directory, String effect) throws IOException {
    Launch receipt =
        Payloads.read(
            new String(SearchFiles.read(directory, "launch.json", 4096), StandardCharsets.UTF_8),
            Launch.class);
    if (!effect.equals(receipt.effect())) throw new IOException("Native receipt scope mismatch");
    return receipt;
  }

  public static void signal(ProcessIdentity expected, boolean force) throws IOException {
    if (expected == null) return;
    var handle = ProcessHandle.of(expected.pid());
    if (!stopped(expected) && handle.isPresent()) {
      // Capture the handle before checking /proc; its native start identity protects PID reuse.
      if (force) handle.get().destroyForcibly();
      else handle.get().destroy();
    }
  }

  public static void signal(NativeIdentity identity, boolean force) throws IOException {
    signal(identity.launcher(), force);
    signal(identity.bubblewrap(), force);
    signal(identity.init(), force);
  }
}
