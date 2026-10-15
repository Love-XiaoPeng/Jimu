package io.github.lovexiaopeng.jimu.adapters.runtime.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Linux /proc start ticks avoid cross-JVM wall-clock conversion differences. */
final class LinuxProcessReceipt {
  private LinuxProcessReceipt() {}

  static long startTicks(long pid) throws IOException {
    String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
    return Long.parseLong(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]);
  }
}
