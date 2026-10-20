package io.github.lovexiaopeng.jimu.model.api;

import java.util.Map;

/**
 * Non-secret admission snapshot. Versioned credentials and profiles must never be edited in place.
 */
public record ModelSnapshot(
    int version,
    String profile,
    int configVersion,
    String adapter,
    String baseUrl,
    String model,
    String revision,
    String authMode,
    String credentialRef,
    int credentialVersion,
    String transportPolicy,
    int connectTimeoutMs,
    int requestTimeoutMs,
    int responseBytes,
    boolean stream,
    int maxOutputTokens,
    String outputLimitField,
    int outputMarginTokens,
    Capabilities capabilities,
    Map<String, Boolean> providerOptions,
    boolean paid,
    Price price,
    InputLimit inputLimit) {
  public ModelSnapshot {
    if (baseUrl != null) baseUrl = baseUrl.replaceAll("/+$", "");
    providerOptions =
        providerOptions == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(providerOptions));
  }

  public record Capabilities(
      boolean call,
      boolean tools,
      boolean streamText,
      boolean streamTools,
      boolean usageCall,
      boolean usageStream,
      boolean parallelToolField,
      long contextLimit,
      String evidence) {}

  /** Rates are integer micro-CNY per million tokens, without assuming cache discounts. */
  public record Price(
      String version,
      String currency,
      long inputMicrosPerMillion,
      long outputMicrosPerMillion,
      long fixedMicros,
      long inputTokenUpper,
      String boundEvidence) {}

  /**
   * Optional complete-input gate; proof names select host implementations, never operator formulas.
   */
  public record InputLimit(int version, long maxTokens, String proof) {}
}
