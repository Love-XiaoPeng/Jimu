package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/** The host binds this port to one immutable invocation slot. */
@FunctionalInterface
public interface TodoPort {
  record Item(String content, String status) {}

  record Counts(int pending, int inProgress, int completed) {}

  record Result(List<Item> todos, Counts counts) {
    public Result {
      todos = List.copyOf(todos);
    }
  }

  Result replace(List<Item> todos);
}
