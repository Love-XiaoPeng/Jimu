package io.github.lovexiaopeng.jimu.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.runtime.web.DisabledSearch;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ExecutionContext;
import io.github.lovexiaopeng.jimu.plugins.builtin.websearch.SearchProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisabledSearchTest {
  @Test
  void disabledProviderAndCancellationFailBeforeAnyExternalEffect() {
    var input = new SearchProvider.Input(List.of("公开资料"));
    assertEquals(
        "SEARCH_NOT_CONFIGURED",
        assertThrows(
                TaskFailure.class,
                () ->
                    new DisabledSearch()
                        .search(
                            input,
                            new ExecutionContext("test", Instant.now().plusSeconds(30), () -> {})))
            .code());
    assertEquals(
        "CANCEL_REQUESTED",
        assertThrows(
                TaskFailure.class,
                () ->
                    new DisabledSearch()
                        .search(
                            input,
                            new ExecutionContext(
                                "test",
                                Instant.now().plusSeconds(30),
                                () -> {
                                  throw new TaskFailure(409, "CANCEL_REQUESTED");
                                })))
            .code());
    for (var queries :
        List.of(
            List.<String>of(), List.of(" "), List.of("x", "x"), List.of("a", "b", "c", "d", "e")))
      assertThrows(IllegalArgumentException.class, () -> new SearchProvider.Input(queries));
  }
}
