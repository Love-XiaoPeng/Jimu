package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.readiness.api.Readiness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ReadinessController {
  private final Readiness readiness;

  public ReadinessController(Readiness readiness) {
    this.readiness = readiness;
  }

  @GetMapping("/readyz")
  public ResponseEntity<ReadinessResponse> ready() {
    var snapshot = readiness.snapshot();
    var response = new ReadinessResponse(snapshot.role(), snapshot.status(), snapshot.plugins());
    return ResponseEntity.status("UP".equals(snapshot.status()) ? 200 : 503).body(response);
  }

  public record ReadinessResponse(String role, String status, int plugins) {}
}
