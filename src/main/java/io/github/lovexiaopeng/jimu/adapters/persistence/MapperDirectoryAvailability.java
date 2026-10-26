package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.entity.InstallationEntity;
import io.github.lovexiaopeng.jimu.adapters.persistence.plugin.mapper.InstallationMapper;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAvailability;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Two-column MP query on the same DS/nullable query; construction and row fields stay lazy. */
final class MapperDirectoryAvailability implements DirectoryAvailability {
  private final JdbcLedger ledger;
  private final Identity identity;

  MapperDirectoryAvailability(JdbcLedger ledger, Identity identity) {
    this.ledger = ledger;
    this.identity = identity;
  }

  public boolean enabled() {
    return ledger.directoryEnabled;
  }

  public ModelSnapshot selected() {
    return ledger.models.selected(identity.tenant());
  }

  public void requireDirectory(ModelSnapshot model) {
    ledger.models.requireDirectory(model);
  }

  public List<Installation> installed() {
    var wrapper = new LambdaQueryWrapper<InstallationEntity>();
    wrapper
        .select(InstallationEntity::getPluginVersion, InstallationEntity::getPluginDigest)
        .eq(InstallationEntity::getTenantId, identity.tenant())
        .eq(InstallationEntity::getPluginId, ToolCapability.WORKING_DIRECTORY);
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

  public ToolDescriptor descriptor() {
    return ledger.directories.descriptor();
  }
}
