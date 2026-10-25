package io.github.lovexiaopeng.jimu.kernel.todo.port;

import java.util.List;

/** Independent lazy reads at the original result-source qualification positions. */
public interface TodoResultSources {
  boolean appliedRevisionPresent();

  long appliedRevision();

  long expectedRevision();

  String state();

  String tool();

  boolean committedAttemptPresent();

  boolean committedFencePresent();

  boolean eventSequencePresent();

  boolean committedAtPresent();

  List<?> attempts();

  String contentState();

  String outputSnapshot();

  String hash(String snapshot);

  String outputDigest();
}
