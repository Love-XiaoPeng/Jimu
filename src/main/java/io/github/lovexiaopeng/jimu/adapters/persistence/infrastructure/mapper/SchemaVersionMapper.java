package io.github.lovexiaopeng.jimu.adapters.persistence.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;

/** 仅查询 Flyway 能力表，不读取业务数据；不执行 repair 或 DDL。 */
@Mapper
public interface SchemaVersionMapper {
  int requiredMigrations();

  int successfulMigrations();
}
