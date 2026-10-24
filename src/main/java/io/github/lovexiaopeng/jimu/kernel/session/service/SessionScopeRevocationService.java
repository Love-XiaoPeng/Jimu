package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;

/** owner先于输入拒绝；撤销与取消／计账在同一绝对截止事务内。 */
public interface SessionScopeRevocationService {
  void revoke(Identity identity, String caller, boolean validRequest, long deadlineNanos);
}
