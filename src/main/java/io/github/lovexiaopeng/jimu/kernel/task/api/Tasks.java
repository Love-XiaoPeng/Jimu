package io.github.lovexiaopeng.jimu.kernel.task.api;

import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
import java.time.Instant;
import java.util.List;

/** Durable use cases. Identity is supplied by the trusted authenticator, never request JSON. */
public interface Tasks {
  record Identity(String tenant, String actor) {}

  record Budget(
      int deadlineSeconds, int maxModelRequests, int maxToolInvocations, Long maxCostMicros) {}

  record Request(
      String goal,
      List<String> capabilityIds,
      List<String> resourceIds,
      Budget budget,
      List<TaskImages.Descriptor> images) {
    public Request(
        String goal, List<String> capabilityIds, List<String> resourceIds, Budget budget) {
      this(goal, capabilityIds, resourceIds, budget, null);
    }

    public Request {
      capabilityIds = List.copyOf(capabilityIds);
      resourceIds = List.copyOf(resourceIds);
      if (images != null) images = List.copyOf(images);
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
      return List.of(
              TaskState.SUCCEEDED,
              TaskState.FAILED,
              TaskState.CANCELLED,
              TaskState.NEEDS_RECONCILIATION)
          .contains(state);
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

  default View submit(
      Identity identity, String key, Request request, List<TaskImages.Input> images) {
    if (!images.isEmpty()) throw new TaskFailure(503, ImageErrorCode.IMAGE_STORE_UNAVAILABLE);
    return submit(identity, key, request);
  }

  View get(Identity identity, String taskId);

  View cancel(Identity identity, String taskId);

  List<String> capabilities(Identity identity);

  List<Event> events(Identity identity, String taskId, long after, int limit);

  Download download(Identity identity, String artifactId);
}
