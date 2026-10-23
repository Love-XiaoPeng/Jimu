package io.github.lovexiaopeng.jimu.kernel.snapshot.api;

/** 原快照UTF-8/SHA256字节契约；内核不依赖序列化框架。 */
@FunctionalInterface
public interface SnapshotDigest {
  String hash(String value);
}
