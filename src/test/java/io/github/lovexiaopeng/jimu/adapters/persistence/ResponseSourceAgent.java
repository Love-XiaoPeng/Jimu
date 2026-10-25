package io.github.lovexiaopeng.jimu.adapters.persistence;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/** 仅私有 worker 来源观测：保存原 Payloads.json 返回串，在账本调用前暂停，不重序列化。 */
public final class ResponseSourceAgent {
  private static Path folder;

  public static void premain(String arguments, Instrumentation instrumentation) {
    folder = Path.of(arguments);
    instrumentation.addTransformer(
        new ClassFileTransformer() {
          @Override
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> redefining,
              ProtectionDomain domain,
              byte[] bytes) {
            if (!name.equals(
                "io/github/lovexiaopeng/jimu/adapters/ai/springai/DurableAgent$Session"))
              return null;
            var reader = new ClassReader(bytes);
            var writer = new ClassWriter(reader, 0);
            int[] sites = {0};
            reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                  @Override
                  public MethodVisitor visitMethod(
                      int access,
                      String method,
                      String descriptor,
                      String signature,
                      String[] errors) {
                    var next = super.visitMethod(access, method, descriptor, signature, errors);
                    if (!method.equals("call")) return next;
                    return new MethodVisitor(Opcodes.ASM9, next) {
                      private boolean armed;

                      @Override
                      public void visitLdcInsn(Object value) {
                        super.visitLdcInsn(value);
                        if ("M1_RESPONSE".equals(value)) armed = true;
                      }

                      @Override
                      public void visitMethodInsn(
                          int opcode, String owner, String method, String desc, boolean itf) {
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                        if (armed
                            && owner.equals("io/github/lovexiaopeng/jimu/adapters/codec/Payloads")
                            && method.equals("json")
                            && desc.equals("(Ljava/lang/Object;)Ljava/lang/String;")) {
                          armed = false;
                          sites[0]++;
                          super.visitInsn(Opcodes.DUP);
                          super.visitMethodInsn(
                              Opcodes.INVOKESTATIC,
                              "io/github/lovexiaopeng/jimu/adapters/persistence/ResponseSourceAgent",
                              "capture",
                              "(Ljava/lang/String;)V",
                              false);
                        }
                      }

                      @Override
                      public void visitMaxs(int stack, int locals) {
                        super.visitMaxs(stack + 1, locals);
                      }
                    };
                  }
                },
                0);
            if (sites[0] != 1)
              throw new IllegalStateException("Expected one original response capture");
            try {
              Files.write(folder.resolve("original-session.class"), bytes);
              Files.write(folder.resolve("observed-session.class"), writer.toByteArray());
            } catch (java.io.IOException error) {
              throw new IllegalStateException(error);
            }
            return writer.toByteArray();
          }
        });
  }

  public static void capture(String original) {
    try {
      Files.writeString(folder.resolve("worker-original-response.json"), original);
      Files.writeString(
          folder.resolve("converted-ready"),
          "before ledger.modelResponded; no response transaction entered");
      new java.util.concurrent.CountDownLatch(1).await();
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
  }
}
