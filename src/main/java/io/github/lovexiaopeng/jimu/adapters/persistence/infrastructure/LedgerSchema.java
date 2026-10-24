package io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure;

import io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.mapper.SchemaVersionMapper;

/** 与旧制品相同的精确 schema22 门禁，经真实 MyBatis 调用链执行。 */
public final class LedgerSchema {
  private final SchemaVersionMapper mapper;

  public LedgerSchema(SchemaVersionMapper mapper) {
    this.mapper = mapper;
  }

  public void check() {
    if (mapper.requiredMigrations() != 22)
      throw new IllegalStateException("Required migration unavailable");
    if (mapper.successfulMigrations() != 22)
      throw new IllegalStateException("Unsupported schema version");
  }
}
