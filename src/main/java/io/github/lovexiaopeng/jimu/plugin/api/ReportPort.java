package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/** Host-bound publisher. The plugin supplies file receipts, never a producer or upload ID. */
public interface ReportPort {
  record Published(
      String artifactId, String path, long version, String sha256, long size, String mediaType) {}

  record Result(List<Published> files) {
    public Result {
      files = List.copyOf(files);
    }
  }

  Result publish(List<FilePort.Snapshot> files);
}
