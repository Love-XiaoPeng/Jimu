package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.mapper.InstallationMapper;
import io.github.lovexiaopeng.jimu.adapters.runtime.model.ModelImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.kernel.task.port.TaskCapabilities;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** 同 DS/nullable query 的单列 MP 清单与原资格回调，不承载过滤或缓存。 */
final class MapperTaskCapabilities implements TaskCapabilities {
  private final JdbcLedger ledger;
  private final Identity identity;
  private final LedgerUnitOfWork transaction;
  private final BooleanSupplier directory;
  private final BooleanSupplier todo;
  private final Predicate<Boolean> session;
  private final BooleanSupplier trace;
  private final BooleanSupplier prior;

  MapperTaskCapabilities(
      JdbcLedger ledger,
      Identity identity,
      LedgerUnitOfWork transaction,
      BooleanSupplier directory,
      BooleanSupplier todo,
      Predicate<Boolean> session,
      BooleanSupplier trace,
      BooleanSupplier prior) {
    this.ledger = ledger;
    this.identity = identity;
    this.transaction = transaction;
    this.directory = directory;
    this.todo = todo;
    this.session = session;
    this.trace = trace;
    this.prior = prior;
  }

  public <T> T transaction(Supplier<T> work) {
    return transaction.required(work);
  }

  public void identity() {
    ledger.identity(identity);
  }

  public List<String> installed() {
    var wrapper = new LambdaQueryWrapper<InstallationEntity>();
    wrapper
        .select(InstallationEntity::getPluginId)
        .eq(InstallationEntity::getTenantId, identity.tenant())
        .eq(InstallationEntity::getEnabled, true)
        .orderByAsc(InstallationEntity::getPluginId);
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    List<Object> rows =
        ledger
            .mappers
            .session()
            .selectList(InstallationMapper.class.getName() + ".selectObjs", parameters);
    return rows.stream().map(String.class::cast).toList();
  }

  public boolean mcpEnabled() {
    return ledger.mcp.enabled();
  }

  public boolean questionsEnabled() {
    return ledger.questionsEnabled;
  }

  public boolean directoryAvailable() {
    return directory.getAsBoolean();
  }

  public boolean todoAvailable() {
    return todo.getAsBoolean();
  }

  public boolean sessionAvailable(boolean search) {
    return session.test(search);
  }

  public boolean traceAvailable() {
    return trace.getAsBoolean();
  }

  public boolean priorAvailable() {
    return prior.getAsBoolean();
  }

  public boolean searchAvailable() {
    return ledger.searchAvailable.getAsBoolean();
  }

  public boolean imagesAvailable() {
    return ledger.imagesAvailable();
  }

  public ModelSnapshot selected() {
    return ledger.models.selected(identity.tenant());
  }

  public boolean imageModelEnabled(ModelSnapshot model) {
    return ModelImages.enabled(model);
  }
}
