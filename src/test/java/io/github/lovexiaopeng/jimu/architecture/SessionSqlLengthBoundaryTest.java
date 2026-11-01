package io.github.lovexiaopeng.jimu.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import org.junit.jupiter.api.Test;

/** 新计量能力只由具名SQL的内部单槽创建/消费；普通Mapper、HTTP与插件不得制造hint。 */
class SessionSqlLengthBoundaryTest {
  @Test
  void onlyTheLockedProviderSlotMayIssueOrConsumeSqlLengths() {
    var classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.lovexiaopeng.jimu");
    var ordinary =
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(
                "io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionEventHistoryRows$Slot")
            .and()
            .doNotHaveFullyQualifiedName(
                "io.github.lovexiaopeng.jimu.adapters.persistence.query.SessionHistoryRows$Slot");
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(
            "io.github.lovexiaopeng.jimu.adapters.persistence.query.QueryResults")
        .should()
        .callMethod(SessionQueryContext.class, "sqlUtf8LengthResult", String.class)
        .check(classes);
    ordinary
        .should()
        .callMethod(SessionQueryContext.Result.class, "takeSqlUtf8Length")
        .check(classes);
    ordinary
        .should()
        .callMethod(
            SessionQueryContext.Result.class,
            "columnSqlUtf8",
            String.class,
            Object.class,
            SessionQueryContext.SqlUtf8Length.class)
        .check(classes);
  }
}
