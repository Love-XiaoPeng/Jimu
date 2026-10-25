package io.github.lovexiaopeng.jimu.kernel.question.service.impl;

import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionMessages;
import io.github.lovexiaopeng.jimu.kernel.question.service.QuestionMessagesService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.LinkedHashMap;
import java.util.Map;

public final class QuestionMessagesServiceImpl implements QuestionMessagesService {
  public <R, Q, A> Map<String, String> messages(QuestionMessages<R, Q, A> port) {
    var result = new LinkedHashMap<String, String>();
    if (!port.questionTask()) return result;
    for (var row : port.pending(port.tenant(), port.taskId())) {
      if (!port.hash(port.answer(row)).equals(port.answerDigest(row)))
        throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
      result.put(
          port.id(row),
          port.encode(
              1,
              "answer_to_pending_question",
              "ask_user_question",
              port.id(row),
              port.provider(row),
              port.questions(row),
              port.read(port.answer(row))));
    }
    return result;
  }
}
