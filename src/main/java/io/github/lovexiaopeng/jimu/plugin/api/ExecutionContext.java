package io.github.lovexiaopeng.jimu.plugin.api;

import java.time.Instant;

/** Host-issued invocation metadata; never accepted from an HTTP tool argument. */
public record ExecutionContext(String invocationId, Instant deadline, Cancellation cancellation) {}
