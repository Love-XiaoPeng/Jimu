package io.github.lovexiaopeng.jimu.api;

import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {TaskController.class, UserQuestionController.class})
public final class TaskErrors {
  public record Error(String code, String message, String traceId) {}

  @ExceptionHandler(TaskFailure.class)
  public ResponseEntity<Error> failure(TaskFailure failure) {
    return response(failure.status(), failure.code());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Error> invalid(HttpMessageNotReadableException failure) {
    return response(400, "INVALID_REQUEST");
  }

  @ExceptionHandler({
    DataAccessException.class,
    org.springframework.transaction.TransactionException.class
  })
  public ResponseEntity<Error> database(RuntimeException failure) {
    return response(503, "LEDGER_UNAVAILABLE");
  }

  private ResponseEntity<Error> response(int status, String code) {
    return ResponseEntity.status(status).body(new Error(code, code, UUID.randomUUID().toString()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<Error> artifact(IllegalStateException failure) {
    return response(503, "ARTIFACT_UNAVAILABLE");
  }
}
