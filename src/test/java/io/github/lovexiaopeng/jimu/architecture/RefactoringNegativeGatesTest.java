package io.github.lovexiaopeng.jimu.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 隔离目录中编译违规类，用产品同一规则证明拒绝，不向制品注入负例。 */
class RefactoringNegativeGatesTest {
  @TempDir Path directory;

  @Test
  void controllerArtifactStoreAccessFails() throws Exception {
    compile(
        "BadController",
        "package io.github.lovexiaopeng.jimu.api; public class BadController {"
            + " io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore store; }");
    var failure =
        assertThrows(
            AssertionError.class,
            () ->
                PackageBoundariesTest.CONTROLLER_STORAGE.check(
                    new ClassFileImporter().importPath(directory)));
    assertTrue(failure.getMessage().contains("ArtifactStore"));
  }

  @Test
  void kernelMapperAccessFails() throws Exception {
    compile(
        "BadService",
        "package io.github.lovexiaopeng.jimu.kernel.task.service.impl; public class BadService {"
            + " io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.mapper.SchemaVersionMapper mapper; }");
    var failure =
        assertThrows(
            AssertionError.class,
            () ->
                PackageBoundariesTest.KERNEL_MAPPER.check(
                    new ClassFileImporter().importPath(directory)));
    assertTrue(failure.getMessage().contains("SchemaVersionMapper"));
  }

  @Test
  void businessThreadContextCannotBorrowDriverClassName() throws Exception {
    compile(
        "SessionQueryDeadline",
        "package io.github.lovexiaopeng.jimu.kernel.session.service.impl; public class SessionQueryDeadline { private final ThreadLocal<Long> budget = new ThreadLocal<>(); }");
    var failure =
        assertThrows(
            AssertionError.class,
            () ->
                PackageBoundariesTest.BUSINESS_THREAD_CONTEXT.check(
                    new ClassFileImporter().importPath(directory)));
    assertTrue(failure.getMessage().contains("ThreadLocal"));
  }

  private void compile(String name, String source) throws Exception {
    Path file = directory.resolve(name + ".java");
    Files.writeString(file, source);
    assertEquals(
        0,
        ToolProvider.getSystemJavaCompiler()
            .run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                directory.toString(),
                file.toString()));
  }
}
