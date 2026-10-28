package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcLedger.s;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionContexts;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 显式query、原锁序与共享Mapper计账；Map和JSON仅留在适配边界。 */
final class MapperSessionContexts implements SessionContexts {
  private final JdbcLedger ledger;

  MapperSessionContexts(JdbcLedger ledger) {
    this.ledger = ledger;
  }

  public <T> T required(Supplier<T> work) {
    return ledger.sessionOwnerRead(work);
  }

  public Task lock(Lease lease) {
    return bind(ledger.fenced(lease, true));
  }

  Task bind(Map<String, Object> row) {
    return new Task() {
      public String id() {
        return s(row, "id");
      }

      public int version() {
        return ledger.sessions.version(row);
      }

      public long upper() {
        return JdbcLedger.n(ledger.sessions.history(row, true), "next_seq") - 1;
      }

      public List<Recent> recent(long upper) {
        return ledger.sessions.recent(row, upper).stream()
            .map(r -> new Recent(JdbcLedger.n(r, "seq"), s(r, "type")))
            .toList();
      }

      public Scope scope() {
        if (!JdbcSessionScopes.task(row)) return null;
        var scope = ledger.sessionScopes.require(row);
        var targets = new ArrayList<Target>();
        for (var target : scope.authority().path("targets"))
          targets.add(
              new Target(target.path("session_id").asText(), target.path("upper_seq").asLong()));
        return new Scope(
            s(scope.row(), "authority_digest"),
            scope.authority().path("retain_until").asText(),
            targets);
      }

      public String encode(Context context) {
        String raw =
            Payloads.json(
                Map.of(
                    "kind",
                    "session_event_context",
                    "version",
                    context.version(),
                    "session_id",
                    context.sessionId(),
                    "upper_seq",
                    context.upper(),
                    "user_message_seq",
                    0,
                    "recent",
                    context.recent().stream()
                        .map(r -> Map.of("seq", r.seq(), "type", r.type()))
                        .toList()));
        if (context.scope() != null) {
          var node = (ObjectNode) ledger.sessions.contracts.json(raw, 4096);
          node.put("scope_sha256", context.scope().digest());
          node.put("retain_until", context.scope().retainUntil());
          var prior = node.putArray("prior_sessions");
          for (var target : context.scope().targets())
            prior
                .addObject()
                .put("session_id", target.sessionId())
                .put("upper_seq", target.upper());
          if (JdbcSessionScopes.lineage(row)) {
            var lineage = ledger.lineages.verify(s(row, "tenant_id"), s(row, "id"));
            node.put("genesis_sha256", lineage.genesisDigest());
            node.put("graph_sha256", lineage.graphDigest());
          }
          raw = Payloads.json(node);
        }
        ledger.sessions.contract(row).context(raw);
        return raw;
      }

      public void account() {
        ledger.sessions.account(row, false);
      }
    };
  }
}
