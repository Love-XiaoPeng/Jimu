package io.github.lovexiaopeng.jimu.kernel.session.port;

/** 冻结receipt字段和惰性事实/装配边界；JsonNode与有序Map只保留在适配层。 */
public interface SessionReceipts {
  enum Field {
    SEQ("seq"),
    BEFORE("before"),
    AFTER("after"),
    UPPER("upper_seq"),
    TARGET("target_session_id"),
    OUTCOME("outcome"),
    CODE("code"),
    DIGEST("output_sha256"),
    BYTES("output_bytes");

    private final String wire;

    Field(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }
  }

  interface Common {
    String outcome();

    String code();

    String digest();

    long bytes();

    void number(Field field, long value);

    void text(Field field, String value);
  }

  interface Native extends Common {
    long target();

    long before();

    long after();

    long upper();
  }

  interface Scope extends Common {
    void parseNormalized();

    void retainNode(Field field);
  }
}
