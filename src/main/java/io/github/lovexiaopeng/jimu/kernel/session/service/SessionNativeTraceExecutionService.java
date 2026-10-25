package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeTraceExecution;

public interface SessionNativeTraceExecutionService {
  SessionNativeTraceExecution.Result trace(SessionNativeTraceExecution execution);
}
