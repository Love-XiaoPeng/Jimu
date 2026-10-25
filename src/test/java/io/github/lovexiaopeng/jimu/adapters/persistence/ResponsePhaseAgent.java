package io.github.lovexiaopeng.jimu.adapters.persistence;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

/** Test-only phase markers; no JDBC calls, timeout changes, or disk writes in the measured call. */
public final class ResponsePhaseAgent {
  private static final String SELF = ResponsePhaseAgent.class.getName().replace('.', '/');
  private static final ConcurrentLinkedQueue<Mark> MARKS = new ConcurrentLinkedQueue<>();
  private static final ConcurrentLinkedQueue<Artifact> CLASSES = new ConcurrentLinkedQueue<>();
  private static final Map<String, List<String>> METHODS =
      Map.of(
          "io/github/lovexiaopeng/jimu/adapters/persistence/transaction/SpringLedgerUnitOfWork",
          List.of("required"),
          "io/github/lovexiaopeng/jimu/adapters/persistence/JdbcSessionRecords",
          List.of("account"),
          "org/springframework/transaction/support/TransactionTemplate",
          List.of("execute"),
          "org/mybatis/spring/MyBatisExceptionTranslator",
          List.of("translateExceptionIfPossible", "initExceptionTranslator"),
          "org/springframework/jdbc/support/AbstractFallbackSQLExceptionTranslator",
          List.of("translate"),
          "org/springframework/jdbc/support/SQLErrorCodesFactory",
          List.of("resolveErrorCodes", "getErrorCodes"),
          "org/springframework/jdbc/support/JdbcUtils",
          List.of("extractDatabaseMetaData"));

  private record Mark(long nanos, long thread, String phase, Throwable error) {}

  private record Artifact(String name, byte[] original, byte[] observed) {}

  private record Method(
      int access, String name, String descriptor, String signature, String[] errors) {}

  public static void mark(String phase) {
    MARKS.add(new Mark(System.nanoTime(), Thread.currentThread().threadId(), phase, null));
  }

  public static void failed(Throwable error, String phase) {
    MARKS.add(new Mark(System.nanoTime(), Thread.currentThread().threadId(), phase, error));
  }

  public static void premain(String argument, Instrumentation instrumentation) {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> save(Path.of(argument))));
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> type,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!METHODS.containsKey(name)) return null;
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            var methods = new ArrayList<Method>();
            reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                  @Override
                  public MethodVisitor visitMethod(
                      int access,
                      String method,
                      String descriptor,
                      String signature,
                      String[] errors) {
                    if (METHODS.get(name).contains(method)
                        && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                      methods.add(new Method(access, method, descriptor, signature, errors));
                      return super.visitMethod(
                          access, method + "$phaseObserved", descriptor, signature, errors);
                    }
                    return super.visitMethod(access, method, descriptor, signature, errors);
                  }

                  @Override
                  public void visitEnd() {
                    for (var method : methods) wrapper(writer, name, method);
                    super.visitEnd();
                  }
                },
                0);
            byte[] observed = writer.toByteArray();
            CLASSES.add(new Artifact(name, bytes.clone(), observed.clone()));
            return observed;
          }
        });
  }

  private static void wrapper(ClassWriter writer, String owner, Method method) {
    var visitor =
        writer.visitMethod(
            method.access(),
            method.name(),
            method.descriptor(),
            method.signature(),
            method.errors());
    visitor.visitCode();
    String label = owner + "." + method.name() + method.descriptor();
    visitor.visitLdcInsn("ENTER " + label);
    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "mark", "(Ljava/lang/String;)V", false);
    var start = new org.springframework.asm.Label();
    var end = new org.springframework.asm.Label();
    var handler = new org.springframework.asm.Label();
    visitor.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
    visitor.visitLabel(start);
    boolean isStatic = (method.access() & Opcodes.ACC_STATIC) != 0;
    int slot = 0;
    if (!isStatic) visitor.visitVarInsn(Opcodes.ALOAD, slot++);
    for (Type argument : Type.getArgumentTypes(method.descriptor())) {
      visitor.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
      slot += argument.getSize();
    }
    visitor.visitMethodInsn(
        isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
        owner,
        method.name() + "$phaseObserved",
        method.descriptor(),
        false);
    visitor.visitLabel(end);
    visitor.visitLdcInsn("RETURN " + label);
    visitor.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "mark", "(Ljava/lang/String;)V", false);
    visitor.visitInsn(Type.getReturnType(method.descriptor()).getOpcode(Opcodes.IRETURN));
    visitor.visitLabel(handler);
    visitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
    visitor.visitInsn(Opcodes.DUP);
    visitor.visitLdcInsn("THROW " + label);
    visitor.visitMethodInsn(
        Opcodes.INVOKESTATIC, SELF, "failed", "(Ljava/lang/Throwable;Ljava/lang/String;)V", false);
    visitor.visitInsn(Opcodes.ATHROW);
    visitor.visitMaxs(0, 0);
    visitor.visitEnd();
  }

  private static void save(Path folder) {
    try {
      Files.createDirectories(folder);
      var lines = new ArrayList<String>();
      int count = 0;
      for (var mark : MARKS) {
        int index = count++;
        lines.add(mark.nanos() + "\t" + mark.thread() + "\t" + index + "\t" + mark.phase());
        if (mark.error() != null) {
          var text = new java.io.StringWriter();
          mark.error().printStackTrace(new java.io.PrintWriter(text));
          var pending = new java.util.ArrayDeque<Throwable>();
          var seen =
              java.util.Collections.newSetFromMap(
                  new java.util.IdentityHashMap<Throwable, Boolean>());
          pending.add(mark.error());
          while (!pending.isEmpty()) {
            Throwable item = pending.removeFirst();
            if (!seen.add(item)) continue;
            if (item instanceof java.sql.SQLException sql) {
              text.append("\nSQLState=")
                  .append(String.valueOf(sql.getSQLState()))
                  .append(" vendorCode=")
                  .append(Integer.toString(sql.getErrorCode()))
                  .append(" class=")
                  .append(sql.getClass().getName())
                  .append("\n");
              if (sql.getNextException() != null) pending.add(sql.getNextException());
            }
            if (item.getCause() != null) pending.add(item.getCause());
            pending.addAll(List.of(item.getSuppressed()));
          }
          Files.writeString(folder.resolve("error-" + index + ".txt"), text.toString());
        }
      }
      Files.write(folder.resolve("phases.tsv"), lines);
      count = 0;
      for (var artifact : CLASSES) {
        Path target = folder.resolve("classes").resolve(Integer.toString(count++));
        Files.createDirectories(target);
        Files.writeString(target.resolve("name.txt"), artifact.name());
        Files.write(target.resolve("original.class"), artifact.original());
        Files.write(target.resolve("observed.class"), artifact.observed());
      }
    } catch (Throwable failure) {
      System.err.println("PHASE_EVIDENCE_WRITE_FAILURE");
      failure.printStackTrace();
    }
  }
}
