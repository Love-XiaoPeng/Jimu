package io.github.lovexiaopeng.jimu.adapters.runtime.model;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.adapters.persistence.ExecutionScopeBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit CLI entry on the trusted operator channel; not a control or worker startup role. */
public final class ExecutionScopeOperator {
  public record Command(
      ExecutionScopes.Requirement requirement, ExecutionScopeBinding.Input binding) {}

  private ExecutionScopeOperator() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("One non-secret binding file required");
    var command = Payloads.read(Files.readString(Path.of(args[0])), Command.class);
    // Binding validates complete profiles without credentials, a registry, or a test-mode escape.
    var source =
        new DriverManagerDataSource(
            System.getenv("JIMU_OPERATOR_DB_URL"),
            System.getenv("JIMU_OPERATOR_DB_USER"),
            System.getenv("JIMU_OPERATOR_DB_PASSWORD"));
    var receipt =
        new ExecutionScopeBinding(source, command.requirement()).bindOnce(command.binding());
    System.out.println(
        Payloads.json(
            Map.of(
                "scopeDigest",
                receipt.get("scope_digest"),
                "canonicalScope",
                receipt.get("scope_snapshot"),
                "ledgerReadback",
                receipt.get("ledger_readback"),
                "ledgerReadbackDigest",
                receipt.get("ledger_readback_digest"),
                "boundBy",
                receipt.get("bound_by"),
                "boundAtUtc",
                receipt.get("bound_at_utc").toString())));
  }
}
