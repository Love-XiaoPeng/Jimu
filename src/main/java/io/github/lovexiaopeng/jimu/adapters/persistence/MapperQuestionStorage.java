package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import io.github.lovexiaopeng.jimu.adapters.persistence.question.mapper.QuestionStorageMapper;
import java.util.List;
import java.util.Map;
import org.springframework.dao.support.DataAccessUtils;

/** 同DS/UoW/query的单条原汇总；构造不读取task或会话。 */
final class MapperQuestionStorage
    implements io.github.lovexiaopeng.jimu.kernel.question.service.QuestionStorageService.Facts {
  private final JdbcLedger ledger;
  private final Map<String, Object> task;

  MapperQuestionStorage(JdbcLedger ledger, Map<String, Object> task) {
    this.ledger = ledger;
    this.task = task;
  }

  public List<String> capabilities() {
    return ledger.caps(task);
  }

  public Long bytes() {
    String tenant = s(task, "tenant_id");
    String id = s(task, "id");
    return single(
        ledger
            .mappers
            .session()
            .getMapper(QuestionStorageMapper.class)
            .bytes(ledger.queryContext(), tenant, id));
  }

  static Long single(List<Long> rows) {
    // JdbcTemplate.queryForObject uses the same nullable-single-result contract.
    return DataAccessUtils.nullableSingleResult(rows);
  }
}
