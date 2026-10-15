package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Linux /proc start ticks avoid cross-JVM wall-clock conversion differences. */
final class LinuxProcessReceipt {
  private LinuxProcessReceipt() {}

  record Status(long startTicks, String state) {
    boolean exited() {
      return "Z".equals(state) || "X".equals(state);
    }
  }

  static Status status(long pid) throws IOException {
    return read(Path.of("/proc", Long.toString(pid), "stat"));
  }

  static Status read(Path path) throws IOException {
    String stat = Files.readString(path);
    String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
    return new Status(Long.parseLong(fields[19]), fields[0]);
  }

  static long startTicks(long pid) throws IOException {
    return status(pid).startTicks();
  }
}
