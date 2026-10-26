package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugin.api.SearchErrorCode;
import io.github.lovexiaopeng.jimu.plugins.builtin.websearch.SearchProvider;

/** No network client or credential exists until search permission and its price are confirmed. */
public final class DisabledSearch implements SearchProvider {
  @Override
  public Output search(Input input, ExecutionContext context) {
    context.cancellation().throwIfCancelled();
    throw new TaskFailure(503, SearchErrorCode.SEARCH_NOT_CONFIGURED);
  }
}
