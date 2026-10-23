package audit.xiao59;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Isolated wiring probe. No product classes, real connection, SQL execution or migration. */
public class CompatibilityProbe {
  @SpringBootConfiguration
  @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
  static class App {}

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "jimu.durable.enabled", havingValue = "true")
  @MapperScan(basePackageClasses = CompatibilityProbe.class, annotationClass = Mapper.class)
  static class Durable {
    @Bean
    DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/never_connect", "probe", "probe");
    }
  }

  @TableName("task")
  public static class TaskEntity {
    // Existing composite PK: no artificial @TableId or database schema change.
    private String tenantId;
    @TableField("id")
    private String taskId;
    public String getTenantId() { return tenantId; }
    public void setTenantId(String value) { tenantId = value; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { taskId = value; }
  }

  @Mapper
  public interface TaskMapper extends BaseMapper<TaskEntity> {}

  public static void main(String[] args) {
    for (boolean enabled : new boolean[] {false, true}) {
      try (var context = new SpringApplicationBuilder(App.class, Durable.class)
          .web(WebApplicationType.NONE)
          .properties("spring.main.banner-mode=off", "spring.sql.init.mode=never",
              "spring.flyway.enabled=false", "jimu.durable.enabled=" + enabled)
          .run()) {
        boolean factory = !context.getBeansOfType(SqlSessionFactory.class).isEmpty();
        if (factory != enabled) throw new AssertionError("Conditional assembly mismatch");
        if (!enabled && !context.getBeansOfType(DataSource.class).isEmpty())
          throw new AssertionError("Disabled durable context unexpectedly requires datasource");
        if (enabled) {
          var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
          String prefix = TaskMapper.class.getName() + ".";
          if (!configuration.hasStatement(prefix + "insert")
              || !configuration.hasStatement(prefix + "selectList")
              || configuration.hasStatement(prefix + "selectById"))
            throw new AssertionError("Composite mapper method injection mismatch");
          System.out.println("PASS durable=true: MP mapper insert/selectList injected; selectById absent");
        } else System.out.println("PASS durable=false: no datasource or SQL session required");
      }
    }
    System.out.println("PASS Boot4.1.1/MP3.5.17 Java21 wiring; database semantics NOT tested");
  }
}
