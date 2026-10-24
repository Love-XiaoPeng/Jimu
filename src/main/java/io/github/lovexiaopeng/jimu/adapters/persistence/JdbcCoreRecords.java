package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.execution.port.ExecutionRecords;
import org.springframework.jdbc.core.JdbcTemplate;

/** B1 transition only: named record operations; B2 replaces these SQL bodies with Mapper calls. */
final class JdbcCoreRecords {
  private final JdbcLedger ledger;
  private final JdbcTemplate db;

  JdbcCoreRecords(JdbcLedger ledger, JdbcTemplate db) {
    this.ledger = ledger;
    this.db = db;
  }

  ExecutionRecords execution() {
    return new JdbcExecutionRecords(ledger, db);
  }
}
