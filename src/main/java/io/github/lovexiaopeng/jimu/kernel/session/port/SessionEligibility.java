package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.PluginPin;
import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

/** Eligibility values remain lazy within the original caller's unit of work. */
public interface SessionEligibility {
  interface Installation {
    String version();

    String digest();

    long epoch();
  }

  interface Access {
    boolean session();

    void history();

    List<String> capabilities();

    ToolDescriptor descriptor(String name);

    PluginPin pin(String name);

    Installation installation(String name);
  }

  interface Admission {
    boolean readEnabled();

    boolean scope();

    boolean scopeEnabled();

    boolean search();

    boolean searchEnabled();

    boolean trace();

    boolean traceEnabled();

    void history();

    ModelSnapshot snapshot();

    void requireTrace(ModelSnapshot snapshot);

    void requireSearch(ModelSnapshot snapshot);

    void requireRead(ModelSnapshot snapshot);
  }
}
