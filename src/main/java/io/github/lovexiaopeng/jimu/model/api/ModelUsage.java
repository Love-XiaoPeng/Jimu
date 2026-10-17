package io.github.lovexiaopeng.jimu.model.api;

/** Nullable counts mean UNKNOWN. Total usage is never split into invented component counts. */
public record ModelUsage(
    Long inputTokens,
    Long outputTokens,
    Long totalTokens,
    Long cachedTokens,
    Long reasoningTokens,
    String providerId,
    String returnedModel,
    String finishReason) {}
