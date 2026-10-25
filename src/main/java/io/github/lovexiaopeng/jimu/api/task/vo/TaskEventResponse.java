package io.github.lovexiaopeng.jimu.api.task.vo;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import java.time.Instant;
import java.util.List;

/** SSE 事件的原有公开字段；仅投影同一事件，不复制或归一化字段。 */
public record TaskEventResponse(
    int eventVersion,
    String eventId,
    String taskId,
    long seq,
    String type,
    Instant occurredAt,
    String status,
    String code,
    List<String> artifactIds) {
  public static TaskEventResponse from(Tasks.Event event) {
    return new TaskEventResponse(
        1,
        event.eventId(),
        event.taskId(),
        event.seq(),
        event.type(),
        event.occurredAt(),
        event.status(),
        event.code(),
        event.artifactIds());
  }
}
