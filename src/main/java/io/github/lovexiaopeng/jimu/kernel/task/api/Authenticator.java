package io.github.lovexiaopeng.jimu.kernel.task.api;

public interface Authenticator {
  Tasks.Identity authenticate(String authorization);
}
