package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;

/** 仅v5 submit使用的内部MVC载体，业务ready不结束写周期。 */
final class SessionTaskAsyncSubmit {
  final SessionTaskWritePump pump;

  SessionTaskAsyncSubmit(long deadline, SessionTaskResponse response) {
    pump = new SessionTaskWritePump(deadline, response);
  }
}
