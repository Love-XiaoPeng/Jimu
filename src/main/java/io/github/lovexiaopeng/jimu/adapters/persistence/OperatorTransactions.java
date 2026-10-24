package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.transaction.port.LedgerUnitOfWork;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 运维专用短事务：绑定沿数据库默认隔离，评估保持只读REPEATABLE_READ。 */
final class OperatorTransactions implements LedgerUnitOfWork {
  private final TransactionTemplate transaction;

  OperatorTransactions(DataSource source, boolean readOnly) {
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setTimeout(10);
    if (readOnly) {
      transaction.setReadOnly(true);
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
  }

  public <T> T required(Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }
}
