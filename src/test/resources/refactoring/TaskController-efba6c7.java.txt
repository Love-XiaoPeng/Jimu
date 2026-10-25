package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.EventFeed;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskSubmission;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@ConditionalOnExpression(
    "'${jimu.durable.enabled:false}' == 'true' and '${jimu.role}' == 'control'")
public final class TaskController {
  public record BudgetRequest(
      Integer deadlineSeconds,
      Integer maxModelRequests,
      Integer maxToolInvocations,
      Long maxCostMicros) {}

  public record SubmitRequest(
      String goal,
      List<String> capabilityIds,
      List<String> resourceIds,
      BudgetRequest budget,
      List<TaskImages.Input> images) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper STRICT =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final TaskService tasks;
  private final Authenticator authentication;
  private final ArtifactService artifacts;
  private final EventFeed feed;

  public TaskController(
      TaskService tasks, Authenticator authentication, ArtifactService artifacts, EventFeed feed) {
    this.tasks = tasks;
    this.authentication = authentication;
    this.artifacts = artifacts;
    this.feed = feed;
  }

  @PostMapping("/api/tasks")
  public ResponseEntity<Tasks.View> submit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      jakarta.servlet.http.HttpServletRequest http)
      throws IOException {
    var identity = authentication.authenticate(authorization);
    byte[] body = http.getInputStream().readNBytes(6291457);
    if (body.length > 6291456) throw new TaskFailure(413, "REQUEST_TOO_LARGE");
    SubmitRequest request;
    try {
      request = STRICT.readValue(body, SubmitRequest.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
      boolean image =
          invalid instanceof com.fasterxml.jackson.databind.JsonMappingException mapping
              && mapping.getPath().stream()
                  .anyMatch(reference -> "images".equals(reference.getFieldName()));
      throw new TaskFailure(400, image ? "INVALID_IMAGE_INPUT" : "INVALID_REQUEST");
    }
    var budget = request == null ? null : request.budget();
    var result =
        tasks.submit(
            identity,
            key,
            request == null
                ? null
                : new TaskSubmission(
                    request.goal(),
                    request.capabilityIds(),
                    request.resourceIds(),
                    budget == null
                        ? null
                        : new TaskSubmission.BudgetInput(
                            budget.deadlineSeconds(),
                            budget.maxModelRequests(),
                            budget.maxToolInvocations(),
                            budget.maxCostMicros()),
                    request.images()));
    return ResponseEntity.accepted()
        .location(URI.create("/api/tasks/" + result.taskId()))
        .body(result);
  }

  private static String taskId(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(404, "NOT_FOUND");
    }
  }

  @GetMapping("/api/capabilities")
  public List<String> capabilities(
      @RequestHeader(value = "Authorization", required = false) String authorization) {
    return tasks.capabilities(authentication.authenticate(authorization));
  }

  @GetMapping("/api/tasks/{id}")
  public Tasks.View get(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id) {
    return tasks.get(authentication.authenticate(authorization), taskId(id));
  }

  @PostMapping("/api/tasks/{id}/cancel")
  public ResponseEntity<Tasks.View> cancel(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id) {
    var result = tasks.cancel(authentication.authenticate(authorization), taskId(id));
    return ResponseEntity.status(result.terminal() ? 200 : 202).body(result);
  }

  @GetMapping("/api/artifacts/{id}/download")
  public ResponseEntity<byte[]> download(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id) {
    var result = artifacts.download(authentication.authenticate(authorization), taskId(id));
    byte[] bytes = result.bytes();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(result.artifact().mediaType()))
        .contentLength(bytes.length)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\""
                + result.artifact().artifactId()
                + (result.artifact().mediaType().equals("text/markdown; charset=utf-8")
                    ? ".md\""
                    : ".txt\""))
        .header("X-Content-Type-Options", "nosniff")
        .eTag(result.artifact().sha256())
        .body(bytes);
  }

  public record Envelope(
      int eventVersion,
      String eventId,
      String taskId,
      long seq,
      String type,
      Instant occurredAt,
      String status,
      String code,
      List<String> artifactIds) {}

  @GetMapping(value = "/api/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", required = false) String last) {
    var identity = authentication.authenticate(authorization);
    tasks.get(identity, taskId(id));
    long cursor = 0;
    if (last != null) {
      if (!last.matches(java.util.regex.Pattern.quote(id) + ":[1-9][0-9]{0,18}"))
        throw new TaskFailure(400, "INVALID_CURSOR");
      try {
        cursor = Long.parseLong(last.substring(id.length() + 1));
      } catch (NumberFormatException e) {
        throw new TaskFailure(400, "INVALID_CURSOR");
      }
    }
    SseEmitter emitter = new SseEmitter(30000L);
    var subscription =
        feed.subscribe(
            authorization,
            id,
            cursor,
            event -> {
              try {
                emitter.send(
                    SseEmitter.event()
                        .id(event.eventId())
                        .name(event.type())
                        .data(
                            new Envelope(
                                1,
                                event.eventId(),
                                event.taskId(),
                                event.seq(),
                                event.type(),
                                event.occurredAt(),
                                event.status(),
                                event.code(),
                                event.artifactIds())));
              } catch (IOException e) {
                throw new IllegalStateException("Subscriber disconnected", e);
              }
            },
            emitter::complete);
    Runnable close =
        () -> {
          try {
            subscription.close();
          } catch (Exception e) {
            throw new IllegalStateException("Subscription close failed", e);
          }
        };
    emitter.onCompletion(close);
    emitter.onTimeout(close);
    emitter.onError(error -> close.run());
    return emitter;
  }
}
