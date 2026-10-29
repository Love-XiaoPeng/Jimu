package io.github.lovexiaopeng.jimu.kernel.session.port;

import java.time.LocalDateTime;
import java.util.List;

/** 当前whole-scope资格所需事实；JSON解码和数据库行留在适配器。 */
public interface SessionScopeRecords {
  /** 原位置每次独立UTF8编码；适配器可在相同query中预留并计量。 */
  default long utf8Bytes(String raw) {
    return raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  record Caller(
      String tenant,
      String id,
      String owner,
      String capabilities,
      String tools,
      long epoch,
      String state,
      LocalDateTime created,
      String requestDigest) {}

  record Scope(
      long recordVersion,
      long authorityVersion,
      String owner,
      String binding,
      String tools,
      String raw,
      String digest,
      long bytes,
      long count,
      String grant,
      LocalDateTime created,
      LocalDateTime retain,
      LocalDateTime invalidated,
      LocalDateTime revoked,
      LocalDateTime nextValidation,
      String content,
      boolean retained) {}

  record Target(
      long version,
      long ordinal,
      String source,
      String raw,
      String digest,
      long bytes,
      String content) {}

  record History(long nextSeq, String binding, LocalDateTime retain) {}

  record AuthorityTarget(
      String id,
      String pins,
      String binding,
      long upperSeq,
      String created,
      String retain,
      String canonical) {}

  record Authority(
      String tenant,
      String caller,
      String owner,
      String tools,
      String binding,
      String created,
      String retain,
      List<AuthorityTarget> targets) {}

  record Validated(
      Scope scope,
      Authority authority,
      List<Caller> sources,
      java.util.Map<String, Scope> sourceScopes) {
    public Validated(Scope scope, Authority authority, List<Caller> sources) {
      this(scope, authority, sources, java.util.Map.of());
    }

    public Validated {
      sources = List.copyOf(sources);
      sourceScopes = java.util.Map.copyOf(sourceScopes);
    }
  }

  /** 已持锁且完成逐项资格验证的受理暂存事实，不是可发布证明。 */
  record SourceFact(
      Caller caller, History history, Scope scope, Authority authority, List<Target> targets) {
    public SourceFact {
      targets = List.copyOf(targets);
    }
  }

  default SourceFact stagedSource(Caller caller, String id) {
    return null;
  }

  default void stageSource(Caller caller, SourceFact fact) {}

  default Authority authority(Caller caller, String raw) {
    validateAuthority(caller, raw);
    return decodeAuthority(raw);
  }

  default void validatedAuthority(Caller caller, Scope scope, Authority authority) {}

  record Live(
      Caller caller,
      String scopeDigest,
      String binding,
      String tools,
      String owner,
      long version,
      long authorityVersion,
      long count,
      long upper,
      String historyBinding,
      java.time.LocalDateTime retain,
      java.time.LocalDateTime scopeRetain,
      java.time.LocalDateTime databaseTime,
      boolean present,
      boolean active,
      boolean quiet) {}

  default Live currentState(Caller caller) {
    throw new UnsupportedOperationException("Current scope projection");
  }

  List<String> callerCapabilities(String tenant, String caller);

  List<String> lockTargets(String tenant, String caller);

  void lockTask(String tenant, String id);

  Scope scope(Caller caller);

  List<Target> targets(Caller caller);

  Caller source(Caller caller, String id);

  boolean hasTargets(Caller caller);

  boolean admissionRetention(Caller caller);

  boolean writersQuiescent(Caller caller);

  boolean activeInvocations(Caller caller);

  boolean unsafeReservations(Caller caller);

  boolean dispatchedModels(Caller caller);

  History history(Caller caller);

  String expectedBinding();

  default String expectedBinding(Caller caller) {
    return expectedBinding();
  }

  void validateAuthority(String raw);

  default void validateAuthority(Caller caller, String raw) {
    validateAuthority(raw);
  }

  Authority decodeAuthority(String raw);

  void invalidateAfterTransaction(Caller caller);
}
