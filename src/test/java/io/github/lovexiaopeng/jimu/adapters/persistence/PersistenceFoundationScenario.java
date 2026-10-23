package io.github.lovexiaopeng.jimu.adapters.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerSchema;
import io.github.lovexiaopeng.jimu.bootstrap.config.LedgerPersistenceConfiguration;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 私有 MySQL 的同库事务差异探针；测试 Entity 不作为已迁业务表计数。 */
public final class PersistenceFoundationScenario {
  @TableName("tenant_budget")
  public static final class BudgetRow {
    @TableId(value = "tenant_id", type = IdType.INPUT)
    private String tenantId;

    private Long storageHeld;

    public String getTenantId() {
      return tenantId;
    }

    public void setTenantId(String value) {
      tenantId = value;
    }

    public Long getStorageHeld() {
      return storageHeld;
    }

    public void setStorageHeld(Long value) {
      storageHeld = value;
    }
  }

  @TableName("actor_access")
  public static final class ActorRow {
    private String tenantId;
    private String actorId;

    public String getTenantId() {
      return tenantId;
    }

    public void setTenantId(String value) {
      tenantId = value;
    }

    public String getActorId() {
      return actorId;
    }

    public void setActorId(String value) {
      actorId = value;
    }
  }

  public interface BudgetMapper extends BaseMapper<BudgetRow> {
    @Select("SELECT CONNECTION_ID()")
    long connectionId();
  }

  public interface ActorMapper extends BaseMapper<ActorRow> {}

  public static void main(String[] args) throws Exception {
    var properties = new Properties();
    try (var stream = Files.newInputStream(Path.of(args[0]))) {
      properties.load(stream);
    }
    try (var context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new PropertiesPropertySource("private", properties));
      context.register(LedgerPersistenceConfiguration.class, MybatisPlusAutoConfiguration.class);
      context.refresh();
      var source = context.getBean(DataSource.class);
      var manager = context.getBean(DataSourceTransactionManager.class);
      var factory = context.getBean(SqlSessionFactory.class);
      check(
          source == manager.getDataSource()
              && source == factory.getConfiguration().getEnvironment().getDataSource(),
          "one-data-source");
      context.getBean(LedgerSchema.class).check();
      check(!factory.getConfiguration().isCacheEnabled(), "cache-disabled");
      factory.getConfiguration().addMapper(BudgetMapper.class);
      factory.getConfiguration().addMapper(ActorMapper.class);
      var session = context.getBean(SqlSessionTemplate.class);
      var mapper = session.getMapper(BudgetMapper.class);
      var actors = session.getMapper(ActorMapper.class);
      check(
          !factory.getConfiguration().hasStatement(ActorMapper.class.getName() + ".selectById"),
          "composite-no-by-id");
      var jdbc = new JdbcTemplate(source);
      var uow = context.getBean(LedgerUnitOfWork.class);
      uow.required(
          () -> {
            check(
                mapper.connectionId() == jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class),
                "mapper-jdbc-same-connection");
            check(
                jdbc.queryForObject("SELECT @@autocommit", Integer.class) == 0, "real-transaction");
            check(mapper.insert(row("b1-commit")) == 1, "base-mapper-insert");
            jdbc.update("UPDATE tenant_budget SET storage_held=7 WHERE tenant_id=?", "b1-commit");
            check(held(mapper, "b1-commit") == 7, "mapper-sees-jdbc-write");
            jdbc.update("UPDATE tenant_budget SET storage_held=9 WHERE tenant_id=?", "b1-commit");
            check(held(mapper, "b1-commit") == 9, "no-local-cache-reuse");
            return null;
          });
      check(held(mapper, "b1-commit") == 9, "shared-commit");
      try {
        uow.required(
            () -> {
              mapper.insert(row("b1-rollback"));
              jdbc.update(
                  "UPDATE tenant_budget SET storage_held=11 WHERE tenant_id=?", "b1-rollback");
              throw new TaskFailure(409, "FENCE_REJECTED");
            });
        throw new AssertionError("rollback rejection missing");
      } catch (TaskFailure failure) {
        check(failure.code().equals("FENCE_REJECTED"), "original-rejection");
      }
      check(
          jdbc.queryForObject(
                  "SELECT COUNT(*) FROM tenant_budget WHERE tenant_id=?",
                  Integer.class,
                  "b1-rollback")
              == 0,
          "shared-rollback");
      var rejected =
          uow.required(
              () -> {
                mapper.insert(row("b1-persisted-rejection"));
                return new TaskFailure(409, "BUSINESS_POLICY_CONFLICT");
              });
      check(
          rejected.code().equals("BUSINESS_POLICY_CONFLICT")
              && held(mapper, "b1-persisted-rejection") == 0,
          "commit-before-deliberate-rejection");
      uow.required(
          () -> {
            mapper.insert(row("b1-other-tenant"));
            for (String tenant : new String[] {"b1-commit", "b1-other-tenant"}) {
              var actor = new ActorRow();
              actor.setTenantId(tenant);
              actor.setActorId("same-actor");
              check(actors.insert(actor) == 1, "composite-insert-" + tenant);
            }
            return null;
          });
      check(
          actors
                  .selectList(
                      new LambdaQueryWrapper<ActorRow>()
                          .select(ActorRow::getTenantId, ActorRow::getActorId)
                          .eq(ActorRow::getTenantId, "b1-commit")
                          .eq(ActorRow::getActorId, "same-actor"))
                  .size()
              == 1,
          "composite-full-key-tenant-isolation");
      jdbc.update(
          "DELETE FROM actor_access WHERE tenant_id IN (?,?)", "b1-commit", "b1-other-tenant");
      jdbc.update(
          "DELETE FROM tenant_budget WHERE tenant_id IN (?,?,?)",
          "b1-commit",
          "b1-other-tenant",
          "b1-persisted-rejection");
      System.out.println("PASS foundation MySQL transactions and BaseMapper");
    }
  }

  private static BudgetRow row(String tenant) {
    var row = new BudgetRow();
    row.setTenantId(tenant);
    row.setStorageHeld(0L);
    return row;
  }

  private static long held(BudgetMapper mapper, String tenant) {
    return mapper
        .selectOne(
            new LambdaQueryWrapper<BudgetRow>()
                .select(BudgetRow::getTenantId, BudgetRow::getStorageHeld)
                .eq(BudgetRow::getTenantId, tenant))
        .getStorageHeld();
  }

  private static void check(boolean condition, String name) {
    if (!condition) throw new AssertionError(name);
    System.out.println("PASS " + name);
  }
}
