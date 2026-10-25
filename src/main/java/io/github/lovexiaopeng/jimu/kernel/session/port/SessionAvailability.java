package io.github.lovexiaopeng.jimu.kernel.session.port;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;
import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import java.util.List;

/** 发现路径原资格与投影的惰性端口；沿调用者原事务，不建立会话 queryView。 */
public interface SessionAvailability {
  boolean readEnabled();

  boolean searchEnabled();

  boolean traceEnabled();

  boolean priorEnabled();

  ModelSnapshot selected();

  void requireTrace(ModelSnapshot model);

  void requireSearch(ModelSnapshot model);

  void requireRead(ModelSnapshot model);

  ToolDescriptor traceRead();

  ToolDescriptor traceSearch();

  ToolDescriptor trace();

  ToolDescriptor priorRead();

  ToolDescriptor prior();

  ToolDescriptor readDescriptor(boolean search);

  ToolDescriptor searchDescriptor();

  List<Installation> installed(String plugin);

  Installation readInstallation();

  Installation searchInstallation();

  interface Installation {
    String version();

    String digest();
  }
}
