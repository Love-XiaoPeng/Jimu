package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionInput;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.plugins.builtin.interaction.AskUserQuestionPlugin.Input;
import java.util.Map;

/** Only stores references; codecs and map reads run when requested by the service. */
final class LedgerQuestionInput implements QuestionInput<PluginPin, Input> {
  private final JdbcLedger ledger;
  private final Map<String, Object> question;
  private final Map<String, Object> task;

  LedgerQuestionInput(JdbcLedger ledger, Map<String, Object> question, Map<String, Object> task) {
    this.ledger = ledger;
    this.question = question;
    this.task = task;
  }

  public long recordVersion() {
    return n(question, "record_version");
  }

  public long questionVersion() {
    return n(question, "question_version");
  }

  public String bindingDigest() {
    return s(question, "binding_digest");
  }

  public String taskBindingDigest() {
    return s(task, "question_binding_digest");
  }

  public String requestSnapshot() {
    return s(question, "request_snapshot");
  }

  public String hash(String raw) {
    return Payloads.hash(raw);
  }

  public String requestDigest() {
    return s(question, "request_digest");
  }

  public PluginPin pin() {
    return ledger.tools(task).pins().get("ask_user_question");
  }

  public String version(PluginPin pin) {
    return pin.version();
  }

  public Input input(String version, String raw) {
    return ledger.questions.input(version, raw);
  }
}
