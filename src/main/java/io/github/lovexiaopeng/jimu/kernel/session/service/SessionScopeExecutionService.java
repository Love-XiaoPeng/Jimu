package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Prior;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.ReadResult;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.ScopeRead;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.SearchResult;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeExecution.Window;

/** 原来源扫描/读取窗口编排，原预算生命周期与拒绝顺序。 */
public interface SessionScopeExecutionService {
  SearchResult prior(Prior prior);

  ReadResult read(ScopeRead read);

  ReadResult window(Window window);
}
