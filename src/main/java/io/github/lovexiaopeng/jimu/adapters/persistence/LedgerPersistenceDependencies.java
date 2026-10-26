package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.adapters.persistence.transaction.SpringLedgerUnitOfWork;
import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Root-owned persistence wiring, shared unchanged by independently owned query views. */
record LedgerPersistenceDependencies(
    DataSource source,
    DataSourceTransactionManager manager,
    LedgerUnitOfWork unitOfWork,
    Runnable schemaCheck,
    CoreMappers mappers) {
  static LedgerPersistenceDependencies create(
      DataSource source,
      DataSourceTransactionManager manager,
      LedgerUnitOfWork unitOfWork,
      Runnable schemaCheck,
      CoreMappers mappers) {
    var shared = manager == null ? new DataSourceTransactionManager(source) : manager;
    return new LedgerPersistenceDependencies(
        source,
        shared,
        unitOfWork == null ? new SpringLedgerUnitOfWork(shared) : unitOfWork,
        schemaCheck,
        mappers);
  }
}
