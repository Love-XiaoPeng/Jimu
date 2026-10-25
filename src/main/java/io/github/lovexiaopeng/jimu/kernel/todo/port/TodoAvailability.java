package io.github.lovexiaopeng.jimu.kernel.todo.port;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

/** Original catalog values, read lazily in the caller's existing unit of work. */
public interface TodoAvailability {
  boolean enabled();

  ModelSnapshot selected();

  void requireTodo(ModelSnapshot model);

  List<Installation> installed();

  ToolDescriptor descriptor();

  boolean businessPolicyPresent();

  boolean scopeRequirementPresent();

  boolean evaluationRunIdPresent();

  interface Installation {
    String version();

    String digest();
  }
}
