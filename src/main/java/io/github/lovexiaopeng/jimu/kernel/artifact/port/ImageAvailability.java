package io.github.lovexiaopeng.jimu.kernel.artifact.port;

/** Lazy checks in the caller's existing context; existence and check read the store separately. */
public interface ImageAvailability {
  boolean storePresent();

  void checkStore();

  void checkCodec();
}
