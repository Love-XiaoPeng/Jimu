package io.github.lovexiaopeng.jimu.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PackageBoundariesTest {
  static final ArchRule CONTROLLER_STORAGE =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .and()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore");
  static final ArchRule KERNEL_MAPPER =
      noClasses()
          .that()
          .resideInAnyPackage("..kernel..", "..execution..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.ibatis..", "com.baomidou..", "..adapters.persistence..");

  @Test
  void realProductionClassesRespectModuleAndCapabilityBoundaries() {
    var classes = new ClassFileImporter().importPath(Path.of("target/classes"));
    assertTrue(
        classes.size() >= 20, "Architecture scan must contain actual Jimu production classes");
    classes()
        .that()
        .resideInAPackage("..plugin.api..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "..plugin.api..")
        .check(classes);
    classes()
        .that()
        .resideInAPackage("..model.api..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", "..model.api..")
        .check(classes);
    noClasses()
        .that()
        .resideInAnyPackage("..kernel..", "..execution..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..adapters..",
            "..plugins..",
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "org.apache.ibatis..",
            "com.baomidou..",
            "com.fasterxml.jackson..",
            "tools.jackson..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .and()
        .haveSimpleNameEndingWith("Controller")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..plugins..",
            "..adapters..",
            "java.sql..",
            "javax.sql..",
            "org.apache.ibatis..",
            "com.baomidou..",
            "..service.impl..")
        .check(classes);
    CONTROLLER_STORAGE.check(classes);
    KERNEL_MAPPER.check(classes);
    // B1 唯一过渡例外：operator bindOnce 将在 B2 迁 Mapper，不是最终 JDBC 豁免。
    noClasses()
        .that()
        .resideInAPackage("..adapters.runtime..")
        .and()
        .doNotHaveFullyQualifiedName(
            "io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopeOperator")
        .and()
        .doNotHaveFullyQualifiedName(
            "io.github.lovexiaopeng.jimu.adapters.runtime.model.ExecutionScopeOperator$Command")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..adapters.persistence..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..plugins..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..kernel..",
            "..adapters..",
            "org.springframework..",
            "java.net..",
            "java.io..",
            "java.nio.file..")
        .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackage("..bootstrap..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..bootstrap..")
        .check(classes);
    noClasses()
        .that()
        .resideOutsideOfPackages("..bootstrap..", "..adapters.runtime..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.Thread")
        .check(classes);
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(
            "io.github.lovexiaopeng.jimu.adapters.runtime.search.NativeRunner")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("java.lang.ProcessBuilder")
        .check(classes);
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("sun..", "jdk.internal..", "java.lang.reflect..")
        .check(classes);
    // No production call site is admitted to terminate the JVM in P1.
    noClasses().should().callMethod(System.class, "exit", int.class).check(classes);
    noClasses().should().callMethod(Runtime.class, "exit", int.class).check(classes);
    noClasses().should().callMethod(Runtime.class, "halt", int.class).check(classes);
    slices().matching("io.github.lovexiaopeng.jimu.(*)..").should().beFreeOfCycles().check(classes);
    slices()
        .matching("io.github.lovexiaopeng.jimu.kernel.(*)..")
        .should()
        .beFreeOfCycles()
        .check(classes);
    for (var type : classes) {
      for (var dependency : type.getDirectDependenciesFromSelf()) {
        String from = type.getPackageName();
        String to = dependency.getTargetClass().getPackageName();
        String root = "io.github.lovexiaopeng.jimu.kernel.";
        if (from.startsWith(root) && to.startsWith(root)) {
          String fromModule = from.substring(root.length()).split("\\.")[0];
          String toModule = to.substring(root.length()).split("\\.")[0];
          if (!fromModule.equals(toModule))
            assertTrue(
                to.startsWith(root + toModule + ".api")
                    || to.startsWith(root + toModule + ".service") && !to.contains(".service.impl")
                    || to.equals(root + "transaction.port"),
                dependency.toString());
        }
      }
    }
  }
}
