package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/** A host-bound resource port. Connection, credentials and task scope are never tool arguments. */
public interface McpResourcePort {
  record Resource(
      String uri, String name, String title, String description, String mimeType, Long size) {}

  record Template(
      String uriTemplate, String name, String title, String description, String mimeType) {}

  record Content(
      String uri,
      String mimeType,
      String kind,
      String sha256,
      long bytes,
      String text,
      boolean textTruncated) {}

  record Resources(
      int version,
      String server,
      String serverVersion,
      String serverSnapshotDigest,
      String schemaDigest,
      String trust,
      String snapshotId,
      String snapshotSha256,
      List<Resource> resources,
      int page,
      int returned,
      int total,
      String nextCursor) {}

  record Templates(
      int version,
      String server,
      String serverVersion,
      String serverSnapshotDigest,
      String schemaDigest,
      String trust,
      String snapshotId,
      String snapshotSha256,
      List<Template> resourceTemplates,
      int page,
      int returned,
      int total,
      String nextCursor) {}

  record Read(
      int version,
      String server,
      String serverVersion,
      String serverSnapshotDigest,
      String schemaDigest,
      String trust,
      String snapshotId,
      String snapshotSha256,
      String resourceUri,
      List<Content> contents) {}

  Templates listTemplates(String server, String cursor);

  Resources listResources(String server, String cursor);

  Read read(String server, String uri);
}
