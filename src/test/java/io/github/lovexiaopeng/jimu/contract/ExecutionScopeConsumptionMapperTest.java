package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.LedgerMapperSessions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Four scope consumption statements and all six original binding positions. */
class ExecutionScopeConsumptionMapperTest {
  @Test
  void currentSpringMapperScanHasOneBeanPerExistingOwner() {
    var source =
        new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic");
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.setAllowBeanDefinitionOverriding(false);
      context.registerBean(
          "sqlSessionFactory",
          org.apache.ibatis.session.SqlSessionFactory.class,
          () -> LedgerMapperSessions.create(source).getSqlSessionFactory());
      context.registerBean(
          "sqlSessionTemplate",
          org.mybatis.spring.SqlSessionTemplate.class,
          () ->
              new org.mybatis.spring.SqlSessionTemplate(
                  context.getBean(org.apache.ibatis.session.SqlSessionFactory.class)));
      var scanner = new org.mybatis.spring.mapper.ClassPathMapperScanner(context);
      scanner.setAnnotationClass(org.apache.ibatis.annotations.Mapper.class);
      scanner.setSqlSessionTemplateBeanName("sqlSessionTemplate");
      scanner.registerFilters();
      scanner.scan("io.github.lovexiaopeng.jimu.adapters.persistence");
      context.refresh();
      assertEquals(
          1,
          context
              .getBeansOfType(
                  io.github.lovexiaopeng.jimu.adapters.persistence.operator.mapper
                      .ExecutionScopeMapper.class)
              .size());
      assertEquals(
          1,
          context
              .getBeansOfType(
                  io.github.lovexiaopeng.jimu.adapters.persistence.model.mapper
                      .EvaluationBudgetMapper.class)
              .size());
    }
  }

  @Test
  void everyCreationSiteRetainsSqlAndBindingOrder() throws Exception {
    var inventory =
        Payloads.read(
            Files.readString(
                Path.of("docs/verification/XIAO-60/evidence/b2-execution-scope-removed-sql.json")),
            JsonNode.class);
    var config =
        LedgerMapperSessions.create(
                new DriverManagerDataSource(
                    "jdbc:mysql://127.0.0.1:1/unused", "unused", "synthetic"))
            .getConfiguration();
    assertEquals(4, inventory.path("operations").size());
    int binds = 0;
    for (var site : inventory.path("operations")) {
      String id =
          "io.github.lovexiaopeng.jimu.adapters.persistence."
              + site.path("domain").asText()
              + ".mapper."
              + site.path("mapper").asText()
              + "."
              + site.path("name").asText();
      var bound = config.getMappedStatement(id).getBoundSql(Map.of());
      String expected = site.path("original").asText();
      assertEquals(
          expected.replaceAll("\\s+", " ").trim(),
          bound.getSql().replaceAll("\\s+", " ").trim(),
          id);
      assertEquals(site.path("args").size(), bound.getParameterMappings().size(), id);
      for (int index = 0; index < bound.getParameterMappings().size(); index++)
        assertEquals(
            site.path("args").get(index).path("name").asText(),
            bound.getParameterMappings().get(index).getProperty(),
            id);
      binds += bound.getParameterMappings().size();
    }
    assertEquals(6, binds);
  }
}
