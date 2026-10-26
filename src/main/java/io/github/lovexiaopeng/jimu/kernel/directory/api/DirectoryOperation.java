package io.github.lovexiaopeng.jimu.kernel.directory.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class DirectoryOperation {
  public static final String FILES = "FILES";
  public static final String READ = "READ";
  public static final String SET = "SET";

  private DirectoryOperation() {}
}
