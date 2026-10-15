package io.github.lovexiaopeng.jimu.plugins.builtin.webfetch;

public record FetchOutput(int httpStatus, String sourceUrl, String content, boolean truncated) {}
