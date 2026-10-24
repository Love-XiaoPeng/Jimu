package io.github.lovexiaopeng.jimu.adapters.persistence.transaction;

import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 直接复用 Spring REQUIRED；与 Mapper 和过渡账本共享唯一事务管理器。 */
public final class SpringLedgerUnitOfWork implements LedgerUnitOfWork {
  private final TransactionTemplate transactions;

  public SpringLedgerUnitOfWork(PlatformTransactionManager manager) {
    transactions = new TransactionTemplate(manager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transactions.setTimeout(10);
  }

  @Override
  public <T> T required(Supplier<T> work) {
    return transactions.execute(status -> work.get());
  }
}
