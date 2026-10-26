package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PendingQuestionMessagesPortTest {
  @Test
  void constructionDoesNotReadLedgerOrRunCallback() {
    var p = new LedgerPendingQuestionMessages(null);
    var called = new java.util.concurrent.atomic.AtomicBoolean();
    assertThrows(
        NullPointerException.class,
        () ->
            p.transaction(
                null,
                () -> {
                  called.set(true);
                  return java.util.List.of();
                }));
    assertFalse(called.get());
    assertThrows(NullPointerException.class, () -> p.fenced(null, true));
    assertThrows(NullPointerException.class, () -> p.messages(null));
  }
}
