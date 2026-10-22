package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/** Data only. The host binds identity and invocation; answers confer no new authority. */
public interface UserQuestionPort {
  record Option(String label, String description) {}

  record Question(
      String id, String question, String header, List<Option> options, Boolean multi_select) {}

  record Answer(String id, List<String> selected, String custom) {}

  record AnswerBatch(List<Answer> answers, Boolean pending, String callId, String message) {}

  AnswerBatch ask(List<Question> questions, Integer optionalTimeoutSeconds);
}
