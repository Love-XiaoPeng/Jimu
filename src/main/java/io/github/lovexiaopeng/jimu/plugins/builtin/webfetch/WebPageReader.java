package io.github.lovexiaopeng.jimu.plugins.builtin.webfetch;

import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;

public interface WebPageReader {
  FetchOutput fetch(String url, ExecutionContext context);
}
