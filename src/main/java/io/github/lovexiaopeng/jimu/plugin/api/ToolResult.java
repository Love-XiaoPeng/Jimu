package io.github.lovexiaopeng.jimu.plugin.api;

/** A result proposal, not a task terminal state or a published artifact. */
public record ToolResult<O>(O output) {}
