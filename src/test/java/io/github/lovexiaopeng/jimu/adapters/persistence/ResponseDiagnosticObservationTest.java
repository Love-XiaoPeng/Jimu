package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
