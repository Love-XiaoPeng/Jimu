package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionNativeSearchExecution;

public interface SessionNativeSearchExecutionService {
  SessionNativeSearchExecution.Result search(SessionNativeSearchExecution execution);
}
