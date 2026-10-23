package io.github.lovexiaopeng.jimu.adapters.runtime.session;

import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.protocol.StandardSocketFactory;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One deadline owns DNS acquisition, physical sockets, handshake and the synchronous transaction.
 */
public final class SessionQueryDeadline implements AutoCloseable {
  private static final ThreadLocal<SessionQueryDeadline> CURRENT = new ThreadLocal<>();
  private static final Semaphore DNS_SLOTS = new Semaphore(8);
  private static final ScheduledThreadPoolExecutor ALARMS = alarms();
  private final long deadline;
  private final List<Socket> sockets = new ArrayList<>();
  private final ScheduledFuture<?> alarm;
  private boolean closed;

  private static ScheduledThreadPoolExecutor alarms() {
    var executor =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("jimu-session-socket-deadline").factory());
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  public SessionQueryDeadline(long deadline) {
    this.deadline = deadline;
    CURRENT.set(this);
    alarm =
        ALARMS.schedule(
            this::closeSockets, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
  }

  int remainingMillis() {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    return (int) Math.max(1, (remaining + 999999L) / 1000000L);
  }

  private synchronized void register(Socket socket) {
    sockets.add(socket);
    if (closed || System.nanoTime() >= deadline) {
      closeSockets();
      throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    }
  }

  private synchronized void closeSockets() {
    closed = true;
    IOException error = null;
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException failure) {
        if (error == null) error = failure;
        else error.addSuppressed(failure);
      }
    }
    if (error != null) throw new UncheckedIOException(error);
  }

  @Override
  public void close() {
    CURRENT.remove();
    alarm.cancel(false);
    closeSockets();
  }

  /** Only DNS runs off-thread: it cannot acquire a connection or execute JDBC after timeout. */
  private InetAddress[] resolve(String host) throws IOException {
    FutureTask<InetAddress[]> lookup = null;
    try {
      if (!DNS_SLOTS.tryAcquire(remainingMillis(), TimeUnit.MILLISECONDS))
        throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
      lookup = new FutureTask<>(() -> InetAddress.getAllByName(host));
      var operation = lookup;
      Thread.ofVirtual()
          .name("jimu-session-dns")
          .start(
              () -> {
                try {
                  operation.run();
                } finally {
                  DNS_SLOTS.release();
                }
              });
      return lookup.get(remainingMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException failure) {
      throw new TaskFailure(504, "SESSION_QUERY_TIMEOUT");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IOException("Session DNS interrupted", failure);
    } catch (ExecutionException failure) {
      if (failure.getCause() instanceof IOException io) throw io;
      throw new IOException("Session DNS unavailable", failure.getCause());
    } finally {
      if (lookup != null) lookup.cancel(true);
    }
  }

  public static DataSource source(DataSource source) {
    if (source instanceof DelegatingDataSource wrapper) {
      wrapper.setTargetDataSource(source(wrapper.getTargetDataSource()));
      return wrapper;
    }
    if (!(source instanceof DriverManagerDataSource original)) return source;
    var bounded =
        new DriverManagerDataSource(
            original.getUrl(), original.getUsername(), original.getPassword()) {
          @Override
          protected Connection getConnectionFromDriverManager(
              String url, java.util.Properties properties) throws SQLException {
            // URL properties take precedence over Properties; append the scoped factory last.
            return super.getConnectionFromDriverManager(
                url
                    + (url.contains("?") ? "&" : "?")
                    + "socketFactory="
                    + DeadlineSocketFactory.class.getName(),
                properties);
          }
        };
    bounded.setConnectionProperties(original.getConnectionProperties());
    bounded.setCatalog(original.getCatalog());
    bounded.setSchema(original.getSchema());
    return new DelegatingDataSource(original) {
      @Override
      public Connection getConnection() throws SQLException {
        return CURRENT.get() == null ? super.getConnection() : bounded.getConnection();
      }

      @Override
      public Connection getConnection(String user, String password) throws SQLException {
        return CURRENT.get() == null
            ? super.getConnection(user, password)
            : bounded.getConnection(user, password);
      }
    };
  }

  /** Connector/J instantiates this class by name; outside a session query it is never selected. */
  public static final class DeadlineSocketFactory extends StandardSocketFactory {
    @Override
    protected Socket createSocket(PropertySet properties) {
      var socket = super.createSocket(properties);
      CURRENT.get().register(socket);
      return socket;
    }

    @Override
    public <T extends Closeable> T connect(
        String host, int port, PropertySet properties, int loginTimeout) throws IOException {
      var budget = CURRENT.get();
      var local = properties.getStringProperty(com.mysql.cj.conf.PropertyKey.localSocketAddress);
      if (local.getValue() != null && !local.getValue().isEmpty())
        local.setValue(budget.resolve(local.getValue())[0].getHostAddress());
      IOException last = null;
      for (InetAddress address : budget.resolve(host)) {
        budget.remainingMillis();
        try {
          // Pass a resolved numeric address: the driver's own resolver cannot block on DNS.
          T socket =
              super.connect(address.getHostAddress(), port, properties, budget.remainingMillis());
          this.host = host;
          return socket;
        } catch (SocketException failure) {
          last = failure;
        }
      }
      budget.remainingMillis();
      throw last == null ? new SocketException("Session database has no address") : last;
    }

    @Override
    protected int getRealTimeout(int expected) {
      int remaining = CURRENT.get().remainingMillis();
      return expected > 0 ? Math.min(expected, remaining) : remaining;
    }
  }
}
