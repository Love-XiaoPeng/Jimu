package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.api.task.dto.TaskEventCursor;
import io.github.lovexiaopeng.jimu.api.task.dto.TaskRequestDecoder;
import io.github.lovexiaopeng.jimu.api.task.vo.ArtifactDownloadResponse;
import io.github.lovexiaopeng.jimu.api.task.vo.TaskEventResponse;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ArtifactService;
import io.github.lovexiaopeng.jimu.kernel.event.api.EventFeedPolicyV1;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.Authenticator;
import io.github.lovexiaopeng.jimu.kernel.task.api.EventFeed;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.kernel.task.service.TaskService;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
    var request = TaskRequestDecoder.decode(http.getInputStream());
    var result = tasks.submit(identity, key, request);
    return ResponseEntity.accepted()
        .location(URI.create("/api/tasks/" + result.taskId()))
        .body(result);
  }

  private static String taskId(String value) {
    try {
      if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException e) {
      throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
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
    return ArtifactDownloadResponse.from(result);
  }

  @GetMapping(value = "/api/tasks/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @PathVariable String id,
      @RequestHeader(value = "Last-Event-ID", required = false) String last) {
    var identity = authentication.authenticate(authorization);
    tasks.get(identity, taskId(id));
    long cursor = TaskEventCursor.parse(id, last);
    SseEmitter emitter = new SseEmitter(EventFeedPolicyV1.EMITTER_TIMEOUT_MILLIS);
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
                        .data(TaskEventResponse.from(event)));
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
