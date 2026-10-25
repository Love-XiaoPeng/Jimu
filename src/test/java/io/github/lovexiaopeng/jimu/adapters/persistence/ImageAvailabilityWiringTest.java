package io.github.lovexiaopeng.jimu.adapters.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.kernel.artifact.service.impl.ImageAvailabilityServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ImageAvailabilityWiringTest {
  @Test
  void constructionDoesNotReadOrCheckStoreAndSecondReadIsNotCached() {
    var calls = new ArrayList<String>();
    var reference = new AtomicReference<Runnable>(() -> calls.add("old-store"));
    var port =
        new LedgerImageAvailability(
            () -> {
              calls.add("present");
              boolean present = reference.get() != null;
              reference.set(null);
              return present;
            },
            () -> reference.get().run());
    assertTrue(calls.isEmpty());
    assertThrows(
        NullPointerException.class, () -> new ImageAvailabilityServiceImpl().available(port));
    assertEquals(List.of("present"), calls);
  }
}
