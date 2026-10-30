package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.contract.SchemaContract;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Fixed immutable v5 policy representation. Never stores a database-row proof. */
final class HistoryPolicyCodec {
  private static final String DESCRIPTOR =
      "45785fbe6fce0cd72d532ace9b3b1ef59231ac61e3e46884ee2a9b4aa96c76ff";
  private final SessionContracts contracts;
  private final SchemaContract schema;
  private final String wire, rawDigest;

  HistoryPolicyCodec(SessionContracts contracts) throws IOException {
    this.contracts = contracts;
    var d =
        contracts.json(
            new String(
                SessionContracts.checked("storage/history-policy-codec-v1.json", DESCRIPTOR),
                StandardCharsets.UTF_8),
            4096);
    if (d.path("version").asInt() != 1
        || !d.path("codec_id").asText().equals("jimu.history.policy.refs.v2")
        || !d.path("schema_resource").asText().equals("storage/history-policy-refs-v2.schema.json")
        || !d.path("policy_resource").asText().equals("storage/history-policy-v5.json")
        || !d.path("wire_resource").asText().equals("storage/history-policy-refs-v2.json"))
      throw new IllegalArgumentException("History policy descriptor");
    schema =
        new SchemaContract(
            SessionContracts.checked(
                "storage/history-policy-refs-v2.schema.json", d.path("schema_sha256").asText()));
    byte[] policy =
        SessionContracts.checked(
            "storage/history-policy-v5.json", d.path("policy_sha256").asText());
    if (!java.util.Arrays.equals(policy, contracts.binding().getBytes(StandardCharsets.UTF_8)))
      throw new IllegalArgumentException("History logical policy bytes");
    rawDigest = d.path("wire_sha256").asText();
    wire =
        new String(
            SessionContracts.checked("storage/history-policy-refs-v2.json", rawDigest),
            StandardCharsets.UTF_8);
    var parsed = contracts.json(wire, 4096);
    schema.validate(parsed);
    if (!parsed.path("schema_sha256").asText().equals(d.path("schema_sha256").asText())
        || !parsed.path("policy_sha256").asText().equals(d.path("policy_sha256").asText())
        || !parsed
            .path("catalog_digest")
            .asText()
            .equals(contracts.json(contracts.binding(), 8192).path("catalogDigest").asText()))
      throw new IllegalArgumentException("History policy wire pins");
  }

  String wire(SessionQueryContext query) {
    if (query != null)
      query.operation(2); // immutable resource selection/handoff; SQL owns its copy
    return wire;
  }

  boolean matches(String raw, SessionQueryContext query) {
    if (query != null)
      query.operation(32); // dispatch, guards, view/lambda aliases, owner acquire/release
    if (raw == null) return false;
    // Existing full snapshots keep their exact reader, including full comparison cost.
    if (raw.length() == contracts.binding().length())
      return query == null
          ? contracts.binding().equals(raw)
          : query.primitive("history.compare", raw.length(), () -> contracts.binding().equals(raw));
    if (query == null) {
      SessionContracts.unicode(raw);
      if (!Payloads.hash(raw).equals(rawDigest) || !wire.equals(raw)) return false;
      // Exact equality transfers the private canonical wire's startup schema proof.
      return true;
    }
    // Keep the full reservation before allocating the per-read view, aliases and strict encoder.
    // No decoded policy copy is allocated: the immutable logical policy stays package-owned.
    query.retainScratch(this, 4096L + 32L * raw.length());
    try {
      return verify(raw, query);
    } finally {
      // verify's view/closures have returned before their owner is released.
      query.releaseScratch(this);
    }
  }

  private boolean verify(String raw, SessionQueryContext query) {
    var view = new LineageRawView(raw, query); // independent on every SQL raw, never cached
    if (!query.primitive("history.rawDigest", 64, () -> rawDigest.equals(view.digest()))
        || !query.primitive(
            "history.wire", Math.max(wire.length(), raw.length()), () -> wire.equals(raw)))
      return false;
    // Only this exact immutable wire inherits its startup schema proof; no tree is rebuilt.
    return true;
  }
}
