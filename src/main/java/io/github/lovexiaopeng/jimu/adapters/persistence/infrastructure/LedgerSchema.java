package io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.mapper.SchemaVersionMapper;

/** 精确schema23门禁；旧22制品必须在readiness前拒绝新库。 */
public final class LedgerSchema {
  private final SchemaVersionMapper mapper;

  public LedgerSchema(SchemaVersionMapper mapper) {
    this.mapper = mapper;
  }

  public void check() {
    if (mapper.requiredMigrations() != 23)
      throw new IllegalStateException("Required migration unavailable");
    if (mapper.successfulMigrations() != 23)
      throw new IllegalStateException("Unsupported schema version");
  }
}
