package io.github.lovexiaopeng.jimu.kernel.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lovexiaopeng.jimu.api.readiness.controller.ReadinessController;
import io.github.lovexiaopeng.jimu.api.readiness.vo.ReadinessResponse;
import io.github.lovexiaopeng.jimu.kernel.readiness.service.impl.ReadinessServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadinessTest {
  @Test
  void emptyCatalogIsDownWithoutProbingResources() {
    var readiness =
        new ReadinessServiceImpl(
            "control",
            List::of,
            () -> {
              throw new AssertionError("Empty catalog must not probe resources");
            });
    var response = new ReadinessController(readiness).ready();
    assertEquals(503, response.getStatusCode().value());
    assertEquals(new ReadinessResponse("control", "DOWN", 0), response.getBody());
  }
}
