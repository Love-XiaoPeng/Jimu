package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/** 诊断设施必须保留主错、JDBC 链及落盘失败；来源探针只观察原转换返回串。 */
class ResponseDiagnosticObservationTest {
  @TempDir Path directory;

  private static Object exception(Throwable failure) throws Exception {
    var method =
        SessionResponseDiagnosticScenario.class.getDeclaredMethod("exception", Throwable.class);
    method.setAccessible(true);
    return method.invoke(null, failure);
  }

  @Test
  void phaseWrapperPreservesReturnAndImplicitException() throws Exception {
    var transformer = new ClassFileTransformer[1];
    var instrumentation =
        (Instrumentation)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("addTransformer"))
                    transformer[0] = (ClassFileTransformer) args[0];
                  return null;
                });
    ResponsePhaseAgent.premain(directory.toString(), instrumentation);
    String name = "org/springframework/jdbc/support/JdbcUtils";
    var writer = new ClassWriter(0);
    writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    var method =
        writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "extractDatabaseMetaData",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
    method.visitCode();
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitMethodInsn(
        Opcodes.INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;)Ljava/lang/Object;",
        false);
    method.visitInsn(Opcodes.ARETURN);
    method.visitMaxs(1, 1);
    method.visitEnd();
    writer.visitEnd();
    byte[] observed =
        transformer[0].transform(
            getClass().getClassLoader(), name, null, null, writer.toByteArray());
    class Isolated extends ClassLoader {
      Class<?> observedClass() {
        return defineClass(name.replace('/', '.'), observed, 0, observed.length);
      }
    }
    var actual = new Isolated().observedClass().getMethod("extractDatabaseMetaData", Object.class);
    Object value = new Object();
    assertSame(value, actual.invoke(null, value));
    var error =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> actual.invoke(null, new Object[] {null}));
    assertInstanceOf(NullPointerException.class, error.getCause());
  }

  @Test
  void lifecycleProbesKeepConstructorInitializationAndExceptionIdentity() throws Exception {
    var lifecycle =
        ResponsePhaseAgent.class.getDeclaredMethod("lifecycle", MethodVisitor.class, String.class);
    lifecycle.setAccessible(true);
    String name = "privatefixture/Lifecycle";
    var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
    var method =
        (MethodVisitor)
            lifecycle.invoke(
                null,
                writer.visitMethod(
                    Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/RuntimeException;)V", null, null),
                name + ".<init>(Ljava/lang/RuntimeException;)V");
    method.visitCode();
    method.visitVarInsn(Opcodes.ALOAD, 0);
    method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    method.visitVarInsn(Opcodes.ALOAD, 1);
    var end = new org.springframework.asm.Label();
    method.visitJumpInsn(Opcodes.IFNULL, end);
    method.visitVarInsn(Opcodes.ALOAD, 1);
    method.visitInsn(Opcodes.ATHROW);
    method.visitLabel(end);
    method.visitFrame(
        Opcodes.F_FULL, 2, new Object[] {name, "java/lang/RuntimeException"}, 0, null);
    method.visitInsn(Opcodes.RETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    writer.visitEnd();
    byte[] bytes = writer.toByteArray();
    class Isolated extends ClassLoader {
      Class<?> loadFixture() {
        return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
      }
    }
    var constructor = new Isolated().loadFixture().getConstructor(RuntimeException.class);
    assertInstanceOf(Object.class, constructor.newInstance(new Object[] {null}));
    var original = new IllegalStateException("original constructor failure");
    var thrown =
        assertThrows(
            java.lang.reflect.InvocationTargetException.class,
            () -> constructor.newInstance(original));
    assertSame(original, thrown.getCause());
  }

  @Test
  void phaseFilesRetainSqlStateVendorAndSuppressedGraph() throws Exception {
    var primary = new SQLException("phase primary", "08S01", 0);
    primary.setNextException(new SQLException("next", "40001", 1205));
    primary.addSuppressed(new SQLException("cleanup", "08003", 99));
    ResponsePhaseAgent.failed(primary, "unit phase failure");
    var save = ResponsePhaseAgent.class.getDeclaredMethod("save", Path.class);
    save.setAccessible(true);
    save.invoke(null, directory);
    StringBuilder all = new StringBuilder();
    try (var files = Files.list(directory)) {
      for (Path file : files.filter(p -> p.getFileName().toString().startsWith("error-")).toList())
        all.append(Files.readString(file));
    }
    assertTrue(all.toString().contains("SQLState=08S01 vendorCode=0"));
    assertTrue(all.toString().contains("SQLState=40001 vendorCode=1205"));
    assertTrue(all.toString().contains("SQLState=08003 vendorCode=99"));
    assertTrue(all.toString().contains("Suppressed:"));
  }

  @Test
  void retainsCauseSuppressedAndNextSqlException() throws Exception {
    var sql = new SQLException("primary SQL", "40001", 1205);
    sql.setNextException(new SQLException("next SQL", "08007", 0));
    sql.addSuppressed(new SQLException("cleanup SQL", "HY000", 1644));
    var primary = new IllegalStateException("production primary", sql);
    String raw = Payloads.json(exception(primary));
    assertTrue(raw.contains("production primary"));
    assertTrue(raw.contains("Suppressed:"));
    assertTrue(raw.contains("Caused by:"));
    assertTrue(raw.contains("40001") && raw.contains("1205"));
    assertTrue(raw.contains("08007") && raw.contains("next SQL"));
    assertTrue(raw.contains("HY000") && raw.contains("1644"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void fileFailureCannotOverwritePrimaryAndRollbackEvidence() throws Exception {
    var field = SessionResponseDiagnosticScenario.class.getDeclaredField("outcome");
    field.setAccessible(true);
    var outcome = (Map<String, Object>) field.get(null);
    outcome.clear();
    outcome.put("primary", exception(new IllegalArgumentException("original primary")));
    outcome.put(
        "transactionException", exception(new SQLException("rollback unknown", "08007", 0)));
    var save = SessionResponseDiagnosticScenario.class.getDeclaredMethod("save", String.class);
    save.setAccessible(true);
    Path file = directory.resolve("not-a-directory");
    Files.writeString(file, "occupied");
    var stdout = new ByteArrayOutputStream();
    var stderr = new ByteArrayOutputStream();
    var priorOut = System.out;
    var priorErr = System.err;
    try (var output = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        var error = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
      System.setOut(output);
      System.setErr(error);
      save.invoke(null, file.resolve("diagnostic").toString());
    } finally {
      System.setOut(priorOut);
      System.setErr(priorErr);
      outcome.clear();
    }
    for (var bytes : new ByteArrayOutputStream[] {stdout, stderr}) {
      String text = bytes.toString(StandardCharsets.UTF_8);
      assertTrue(text.contains("original primary"));
      assertTrue(text.contains("rollback unknown") && text.contains("08007"));
    }
    assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("EVIDENCE_WRITE_FAILURE"));
    assertEquals("occupied", Files.readString(file));
  }

  @Test
  void sourceProbeInsertsOneObservationAfterOriginalSerialization() throws Exception {
    var transformer = new ClassFileTransformer[1];
    var instrumentation =
        (Instrumentation)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("addTransformer"))
                    transformer[0] = (ClassFileTransformer) args[0];
                  return null;
                });
    ResponseSourceAgent.premain(directory.toString(), instrumentation);
    String name = "io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent$Session";
    byte[] original;
    try (var stream = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
      original = stream.readAllBytes();
    }
    byte[] observed =
        transformer[0].transform(getClass().getClassLoader(), name, null, null, original);
    int[] count = {0};
    new ClassReader(observed)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(
                  int access, String name, String desc, String sig, String[] errors) {
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public void visitMethodInsn(
                      int opcode, String owner, String method, String descriptor, boolean itf) {
                    if (owner.endsWith("/ResponseSourceAgent") && method.equals("capture"))
                      count[0]++;
                  }
                };
              }
            },
            0);
    assertEquals(1, count[0]);
    assertEquals(original.length, Files.size(directory.resolve("original-session.class")));
  }
}
