package io.github.lovexiaopeng.jimu.adapters.persistence;

import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.n;
import static io.github.lovexiaopeng.jimu.adapters.persistence.JdbcSessionRecords.s;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionHistoryViews;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks.Identity;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 同 DS/UoW 的 owner 历史惰性视图，复用公共 owner 锁和原 codec，不预读列。 */
final class MapperSessionHistoryViews implements SessionHistoryViews.Projection {
  private final JdbcSessionRecords records;
  private final Map<String, Object> task;

  MapperSessionHistoryViews(JdbcSessionRecords records, Map<String, Object> task) {
    this.records = records;
    this.task = task;
  }

  static SessionHistoryViews.Owner owner(JdbcLedger ledger, Identity identity, String taskId) {
    return new SessionHistoryViews.Owner() {
      public <T> T required(Supplier<T> work) {
        return ledger.sessionOwnerRead(work);
      }

      public SessionHistoryViews.Projection lock() {
        return new MapperSessionHistoryViews(
            ledger.sessions, ledger.sessionOwner(identity, taskId));
      }
    };
  }

  public SessionHistoryViews.History history() {
    var history = records.history(task, false);
    return new SessionHistoryViews.History() {
      public long nextSeq() {
        return n(history, "next_seq");
      }

      public String contentState() {
        return s(history, "content_state");
      }

      public long retained() {
        return n(history, "retained");
      }

      public String retainUntil() {
        return JdbcSessionRecords.UTC.format((LocalDateTime) history.get("retain_until"));
      }
    };
  }

  public int version() {
    return records.version(task);
  }

  public String sessionId() {
    return s(task, "id");
  }

  public List<?> recent(long upper) {
    return records.recent(task, upper);
  }

  public long asOfSseSeq() {
    return n(task, "next_event_seq");
  }

  public boolean scopeTask() {
    return JdbcSessionScopes.task(task);
  }

  public SessionHistoryViews.Scope requireScope() {
    var scope = records.scopes.require(task);
    return new SessionHistoryViews.Scope() {
      public String digest() {
        return s(scope.row(), "authority_digest");
      }

      public String state() {
        return s(scope.row(), "grant_state");
      }
    };
  }

  public String encode(LinkedHashMap<String, Object> value) {
    return Payloads.json(value);
  }

  public void validateOwner(String raw) {
    records.contract(task).owner(raw);
  }
}
