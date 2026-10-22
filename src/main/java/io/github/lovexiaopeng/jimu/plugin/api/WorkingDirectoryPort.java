package io.github.lovexiaopeng.jimu.plugin.api;

/** Host-bound task directory; paths are logical names inside the fixed workspace. */
public interface WorkingDirectoryPort {
  record Result(String cwd) {}

  Result current();

  Result change(String path);
}
