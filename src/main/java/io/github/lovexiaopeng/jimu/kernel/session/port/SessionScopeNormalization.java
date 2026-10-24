package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 惰性codec与资格边界；JSON节点和原存储行访问留在适配器。 */
public interface SessionScopeNormalization {
  record Read(String session, long seq, int before, int after) {}

  interface SearchObject {
    void query(String query);

    String sortedJson();
  }

  interface Authority {
    long upper(String target);
  }

  interface Input {
    String tool();

    String searchQuery();

    SearchObject searchObject();

    Read read();

    String caller();

    long upper();

    Authority requireScope();

    String writeRead(String target, Read read, long upper);
  }

  /** sameJson先解析expected再解析stored，各沿原4096字节上限。 */
  interface Proof {
    boolean sameJson(String expected, String stored);

    String hash(String raw);

    int bytes(String raw);
  }

  /** 摘要/长度访问保持惰性，保留JSON/摘要拒绝与原异常的优先次序。 */
  record Stored(String raw, Supplier<String> digest, LongSupplier bytes) {}
}
