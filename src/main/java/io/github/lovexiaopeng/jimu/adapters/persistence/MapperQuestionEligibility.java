package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.mapper.InstallationMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.interaction.QuestionContracts;
import io.github.lovexiaopeng.jimu.kernel.question.port.QuestionEligibility;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** No constructor reads; existing codecs and map members execute only when reached. */
final class MapperQuestionEligibility
    implements QuestionEligibility<PluginPin, QuestionContracts.Contract> {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperQuestionEligibility(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public PluginPin pin() {
    return ledger.tools(task).pins().get("ask_user_question");
  }

  public String version(PluginPin pin) {
    return pin.version();
  }

  public String digest(PluginPin pin) {
    return pin.digest();
  }

  public long epoch(PluginPin pin) {
    return pin.installationEpoch();
  }

  public QuestionContracts.Contract contract(String version) {
    return ledger.questions.contract(version);
  }

  public Installation installation() {
    String tenant = s(task, "tenant_id");
    var wrapper = new QueryWrapper<InstallationEntity>();
    wrapper.select("*").eq("tenant_id", tenant).eq("plugin_id", "ask_user_question");
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    List<Map<String, Object>> rows =
        ledger
            .mappers
            .session()
            .selectList(InstallationMapper.class.getName() + ".selectMaps", parameters);
    return first(rows);
  }

  static Row first(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
    return new Row(rows.getFirst());
  }

  public String contractOutputDigest(QuestionContracts.Contract contract) {
    return contract.descriptor().outputSha256();
  }

  public String cleanupState() {
    return s(task, "question_cleanup_state");
  }

  public String bindingSnapshot() {
    return s(task, "question_binding");
  }

  public String hash(String binding) {
    return Payloads.hash(binding);
  }

  public String bindingDigest() {
    return s(task, "question_binding_digest");
  }

  public String expectedBinding(String version) {
    return ledger.questions.binding(version);
  }

  static final class Row implements Installation {
    private final Map<String, Object> values;

    Row(Map<String, Object> row) {
      values = row;
    }

    public String version() {
      return s(values, "plugin_version");
    }

    public String digest() {
      return s(values, "plugin_digest");
    }

    public long epoch() {
      return n(values, "epoch");
    }

    Map<String, Object> values() {
      return values;
    }
  }
}
