package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.mapper.InstallationMapper;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionAvailability;
import io.github.lovexiaopeng.jimu.kernel.shared.api.CommonErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 同 DS/原 nullable query 的 MP 两列投影；仅惰性取值，不编排资格或回退。 */
final class MapperSessionAvailability implements SessionAvailability {
  private final JdbcLedger ledger;
  private final Identity identity;

  MapperSessionAvailability(JdbcLedger ledger, Identity identity) {
    this.ledger = ledger;
    this.identity = identity;
  }

  public boolean readEnabled() {
    return ledger.sessionReadEnabled;
  }

  public boolean searchEnabled() {
    return ledger.sessionSearchEnabled;
  }

  public boolean traceEnabled() {
    return ledger.sessionTraceEnabled;
  }

  public boolean priorEnabled() {
    return ledger.priorSessionSearchEnabled;
  }

  public ModelSnapshot selected() {
    return ledger.models.selected(identity.tenant());
  }

  public void requireTrace(ModelSnapshot model) {
    ledger.models.requireSessionTrace(model);
  }

  public void requireSearch(ModelSnapshot model) {
    ledger.models.requireSessionSearch(model);
  }

  public void requireRead(ModelSnapshot model) {
    ledger.models.requireSessionRead(model);
  }

  public ToolDescriptor traceRead() {
    return ledger.sessions.v3.descriptor();
  }

  public ToolDescriptor traceSearch() {
    return ledger.sessions.searchV3.descriptor();
  }

  public ToolDescriptor trace() {
    return ledger.sessions.trace.descriptor();
  }

  public ToolDescriptor priorRead() {
    return ledger.sessions.v4.descriptor();
  }

  public ToolDescriptor prior() {
    return ledger.sessionScopes.contracts.descriptor();
  }

  public ToolDescriptor readDescriptor(boolean search) {
    return (search ? ledger.sessions.v2 : ledger.sessions.contracts).descriptor();
  }

  public ToolDescriptor searchDescriptor() {
    return ledger.sessions.search.descriptor();
  }

  public List<Installation> installed(String plugin) {
    var wrapper = new LambdaQueryWrapper<InstallationEntity>();
    wrapper
        .select(InstallationEntity::getPluginVersion, InstallationEntity::getPluginDigest)
        .eq(InstallationEntity::getTenantId, identity.tenant())
        .eq(InstallationEntity::getPluginId, plugin)
        .eq(InstallationEntity::getEnabled, true);
    var parameters = new HashMap<String, Object>();
    parameters.put("ew", wrapper);
    parameters.put("query", ledger.queryContext());
    List<Map<String, Object>> rows =
        ledger
            .mappers
            .session()
            .selectList(InstallationMapper.class.getName() + ".selectMaps", parameters);
    return new AbstractList<>() {
      public int size() {
        return rows.size();
      }

      public Installation get(int index) {
        var row = rows.get(index);
        return new Installation() {
          public String version() {
            return s(row, "plugin_version");
          }

          public String digest() {
            return s(row, "plugin_digest");
          }
        };
      }
    };
  }

  private Installation first(List<Installation> rows) {
    if (rows.isEmpty()) throw new TaskFailure(404, CommonErrorCode.NOT_FOUND);
    return rows.getFirst();
  }

  public Installation readInstallation() {
    return first(installed(ToolCapability.SESSION_EVENT_READ));
  }

  public Installation searchInstallation() {
    return first(installed(ToolCapability.SESSION_EVENT_SEARCH));
  }
}
