package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.UserQuestionLedger;
import io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.Answer;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserQuestionContractTest {
  private final QuestionContracts contracts = new QuestionContracts();
  private static final String QUESTION =
      "{\"questions\":[{\"id\":\"q\",\"question\":\"Choose\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"multi_select\":true}]}";

  @Test
  void canonicalMultiselectPreservesCustomAndOriginalDisplayOrder() {
    var questions = contracts.input("0.1.0", QUESTION).questions();
    var answers =
        contracts.canonicalAnswers(
            questions,
            new UserQuestionLedger.Command(
                1, 1, List.of(new Answer("q", List.of("B", "A"), "  unchanged 😀  "))));
    assertEquals(List.of("A", "B"), answers.getFirst().selected());
    assertEquals("  unchanged 😀  ", answers.getFirst().custom());
    String output =
        Payloads.json(
            new io.github.lovexiaopeng.jimu.plugin.api.UserQuestionPort.AnswerBatch(
                answers, null, null, null));
    contracts.output("0.1.0", output);
    assertTrue(!output.contains("pending") && !output.contains("callId"));
    assertEquals(
        "[{\"id\":\"q\",\"selected\":[]}]",
        Payloads.json(
            contracts.canonicalAnswers(
                questions,
                new UserQuestionLedger.Command(1, 1, List.of(new Answer("q", List.of(), null))))));
  }

  @Test
  void strictModelBoundaryRejectsAmbiguousAndOverlargeInput() {
    for (String invalid :
        List.of(
            QUESTION + " {}",
            QUESTION.replace("\"questions\":", "\"questions\":[],\"questions\":"),
            QUESTION.replace("Choose", "😀".repeat(1025)),
            QUESTION.replace("\"label\":\"B\"", "\"label\":\"A\""),
            QUESTION.replace("true", "\"true\""),
            QUESTION.replace("\"id\":\"q\"", "\"id\":\"q\",\"owner\":\"alice\""),
            QUESTION.replace("\"id\":\"q\"", "\"id\":\"q\",\"id\":\"z\""),
            QUESTION.replace("Choose", "\\ud800")))
      assertEquals(
          "TOOL_INPUT_INVALID",
          assertThrows(TaskFailure.class, () -> contracts.input("0.1.0", invalid)).code());
    assertThrows(
        TaskFailure.class,
        () ->
            contracts.input(
                "0.2.0", QUESTION.replace("{\"questions\":", "{\"timeout\":1.0,\"questions\":")));
    assertThrows(
        TaskFailure.class,
        () ->
            contracts.input(
                "0.1.0", QUESTION.replace("{\"questions\":", "{\"timeout\":1,\"questions\":")));
    assertEquals(
        -1,
        contracts
            .input("0.2.0", QUESTION.replace("{\"questions\":", "{\"timeout\":-1,\"questions\":"))
            .timeout());
    contracts.output("0.2.0", contracts.pending("0.2.0", "source-call"));
  }

  @Test
  void answersMatchExactQuestionsAndNeverGrantImplicitApproval() {
    var questions = contracts.input("0.1.0", QUESTION.replace("true", "false")).questions();
    for (var invalid :
        List.of(
            List.of(new Answer("other", List.of(), null)),
            List.of(new Answer("q", List.of("A", "B"), null)),
            List.of(new Answer("q", List.of("A"), "other")),
            List.of(new Answer("q", List.of("bad"), null)),
            List.of(new Answer("q", List.of(), " ")),
            List.of(new Answer("q", List.of(), "😀".repeat(513))),
            List.of(new Answer("q", List.of("A", "A"), null)),
            List.of(new Answer("q", List.of(), null), new Answer("q", List.of(), null))))
      assertEquals(
          "QUESTION_ANSWER_INVALID",
          assertThrows(
                  TaskFailure.class,
                  () ->
                      contracts.canonicalAnswers(
                          questions, new UserQuestionLedger.Command(1, 1, invalid)))
              .code());
  }
}
