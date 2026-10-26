package io.github.lovexiaopeng.jimu.kernel.directory.api;

/** 目录工作区的观测状态，与目录正文清理状态分别归属。 */
public final class DirectoryWorkspaceState {
  public static final String READY = "READY";
  public static final String UNINITIALIZED = "UNINITIALIZED";

  private DirectoryWorkspaceState() {}
}
