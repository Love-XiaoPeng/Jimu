package io.github.lovexiaopeng.jimu.kernel.snapshot.api;

/** 隔离JSON对象编解码；具体保留字段由业务用例明确指定。 */
@FunctionalInterface
public interface SnapshotObjectCodec {
  String withoutField(String snapshot, String field);
}
