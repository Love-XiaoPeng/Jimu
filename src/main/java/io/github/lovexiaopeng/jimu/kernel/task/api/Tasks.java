package io.github.lovexiaopeng.jimu.kernel.task.api;

import java.time.Instant;
import java.util.List;

/** Durable use cases. Identity is supplied by the trusted authenticator, never request JSON. */
public interface Tasks {
  record Identity(String tenant, String actor) {}

  record Budget(int deadlineSeconds, int maxModelRequests, int maxToolInvocations) {}

  record Request(String goal, List<String> capabilityIds, List<String> resourceIds, Budget budget) {
    public Request {
      capabilityIds = List.copyOf(capabilityIds);
      resourceIds = List.copyOf(resourceIds);
    }
  }

  record Artifact(
      String artifactId, String mediaType, long size, String sha256, Instant expiresAt) {}

  record View(
      String taskId,
      String state,
      long revision,
      Instant createdAt,
      Instant updatedAt,
      Instant cancelRequestedAt,
      String code,
      String summary,
      List<Artifact> artifacts) {
    public View {
      artifacts = List.copyOf(artifacts);
    }

    public boolean terminal() {
      return List.of("SUCCEEDED", "FAILED", "CANCELLED", "NEEDS_RECONCILIATION").contains(state);
    }
  }

  record Event(
      String eventId,
      long seq,
      String type,
      String taskId,
      Instant occurredAt,
      String code,
      String status,
      List<String> artifactIds) {
    public Event {
      artifactIds = List.copyOf(artifactIds);
    }
  }

  record Download(Artifact artifact, String tenant, String snapshotKey) {}

  View submit(Identity identity, String key, Request request);

  View get(Identity identity, String taskId);

  View cancel(Identity identity, String taskId);

  List<String> capabilities(Identity identity);

  List<Event> events(Identity identity, String taskId, long after, int limit);

  Download download(Identity identity, String artifactId);
}
