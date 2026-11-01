package io.github.lovexiaopeng.jimu.adapters.persistence.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.lovexiaopeng.jimu.api.SessionLineageController;
import io.github.lovexiaopeng.jimu.api.SessionTaskResponseConfiguration;
import io.github.lovexiaopeng.jimu.api.TaskErrors;
import io.github.lovexiaopeng.jimu.kernel.session.api.SessionQueryContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionEventLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionLineageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionSearchScopeLedger.QueryResources;
import io.github.lovexiaopeng.jimu.kernel.task.api.SessionTaskResponse;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import jakarta.servlet.AsyncEvent;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实MVC异步分派及JSON消费；业务端为具名假ledger/假JDBC，不代表MySQL受理通过。 */
class SessionTaskResponseMvcTest {
  @Test
  void transactionCompletionKeepsResponseUntilProductionPumpConsumesAndFinalNullDispatch()
      throws Exception {
    var fixture = new Harness(false);
    var pending = fixture.pending();
    fixture.complete(pending);
    assertEquals(1, fixture.transaction.commits);
    assertEquals(65536, fixture.query.scratchLive());
    var result = fixture.mvc.perform(asyncDispatch(pending)).andReturn();
    assertEquals(202, result.getResponse().getStatus());
    var original = new MockHttpOutputMessage();
    fixture.json.write(view(), MediaType.APPLICATION_JSON, original);
    assertEquals(original.getBodyAsString(), result.getResponse().getContentAsString());
    fixture.released();
  }

  @Test
  void realAsyncTimeoutDefersRefundUntilProducerReturnsAndReleasesOnce() throws Exception {
    var fixture = new Harness(false);
    var pending = fixture.pending();
    fixture.afterHold =
        () -> {
          var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
          try {
            for (var listener : context.getListeners()) listener.onTimeout(new AsyncEvent(context));
          } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
          }
          assertTrue(fixture.query.scratchLive() > 65536);
          org.junit.jupiter.api.Assertions.assertFalse(
              org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(
                      pending.getRequest())
                  .hasConcurrentResult());
        };
    var search =
        (SessionEventLedger.SearchRequest)
            pending.getRequest().getAttribute("jimu.session.search.request");
    try {
      search.completed().accept(new byte[] {'{', '}'});
    } catch (RuntimeException failure) {
      search.failed().accept(failure);
    }
    var result = fixture.mvc.perform(asyncDispatch(pending)).andReturn();
    assertEquals(504, result.getResponse().getStatus());
    fixture.released();
  }

  @Test
  void serializationExceptionDropsViewAndRefundsExactlyOnce() throws Exception {
    var fixture = new Harness(true);
    var pending = fixture.pending();
    fixture.complete(pending);
    assertThrows(Exception.class, () -> fixture.mvc.perform(asyncDispatch(pending)));
    fixture.released();
  }

  @Test
  void servletErrorCannotUseSpringLifecycleToDispatchWhileProducerStillBorrows() throws Exception {
    var fixture = new Harness(false);
    var pending = fixture.pending();
    var marker = new java.io.IOException("servlet error marker");
    fixture.afterHold =
        () -> {
          var context = (MockAsyncContext) pending.getRequest().getAsyncContext();
          try {
            for (var listener : context.getListeners())
              listener.onError(new AsyncEvent(context, marker));
          } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
          }
          assertTrue(fixture.query.scratchLive() > 65536);
          org.junit.jupiter.api.Assertions.assertFalse(
              org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(
                      pending.getRequest())
                  .hasConcurrentResult());
        };
    fixture.complete(pending);
    assertTrue(
        org.springframework.web.context.request.async.WebAsyncUtils.getAsyncManager(
                pending.getRequest())
            .hasConcurrentResult());
    fixture.released();
  }

  private static Tasks.View view() {
    return new Tasks.View(
        "caller",
        "QUEUED",
        1,
        Instant.EPOCH,
        Instant.EPOCH,
        null,
        null,
        "原文 😀",
        List.of(new Tasks.Artifact("a", "text/plain", 1, "hash", Instant.EPOCH)));
  }

  private static final class Harness {
    final SessionQueryTransactionTest.Fixture transaction =
        new SessionQueryTransactionTest.Fixture();
    final JacksonJsonHttpMessageConverter json = new JacksonJsonHttpMessageConverter();
    final MockMvc mvc;
    SessionQueryContext query;
    SessionTaskResponse response;
    Runnable afterHold = () -> {};

    Harness(boolean failSerialization) {
      var ledger =
          (SessionLineageLedger)
              Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SessionLineageLedger.class},
                  (p, method, args) -> {
                    if (!method.getName().equals("submitSessionLineage") || args.length != 5)
                      throw new AssertionError(method);
                    query = new SessionQueryContext((long) args[3], new QueryResources(0, 0, 0));
                    query.restrictLineage();
                    response = (SessionTaskResponse) args[4];
                    response.bind(
                        new SessionTaskResponse.Budget() {
                          public void retain(Object owner, long bytes) {
                            query.retainScratch(owner, bytes);
                          }

                          public void release(Object owner) {
                            query.releaseScratch(owner);
                          }

                          public void wire(int count) {
                            if (failSerialization) {
                              assertTrue(query.scratchLive() >= 65536 + 8192 + 2048);
                              throw new java.io.UncheckedIOException(
                                  new java.io.IOException("injected serializer budget failure"));
                            }
                            query.operation(count);
                            query.remainingMillis();
                          }
                        });
                    transaction.transaction.required(
                        query,
                        () -> {
                          response.retain(8192);
                          response.hold(view());
                          afterHold.run();
                          return null;
                        });
                    return null;
                  });
      var converters = new ArrayList<HttpMessageConverter<?>>();
      converters.add(json);
      new SessionTaskResponseConfiguration().extendMessageConverters(converters);
      mvc =
          MockMvcBuilders.standaloneSetup(
                  new SessionLineageController(ignored -> new Tasks.Identity("t", "a"), ledger))
              .setControllerAdvice(new TaskErrors())
              .setMessageConverters(converters.toArray(HttpMessageConverter<?>[]::new))
              .addFilters(
                  (request, response, chain) ->
                      chain.doFilter(
                          request,
                          new jakarta.servlet.http.HttpServletResponseWrapper(
                              (jakarta.servlet.http.HttpServletResponse) response) {
                            @Override
                            public jakarta.servlet.ServletOutputStream getOutputStream()
                                throws java.io.IOException {
                              var delegate = super.getOutputStream();
                              return new jakarta.servlet.ServletOutputStream() {
                                public boolean isReady() {
                                  return true;
                                }

                                public void setWriteListener(
                                    jakarta.servlet.WriteListener listener) {
                                  try {
                                    listener.onWritePossible();
                                  } catch (java.io.IOException failure) {
                                    throw new java.io.UncheckedIOException(failure);
                                  }
                                }

                                public void write(int value) throws java.io.IOException {
                                  delegate.write(value);
                                }
                              };
                            }
                          }))
              .build();
      var adapter =
          mvc.getDispatcherServlet()
              .getWebApplicationContext()
              .getBean(
                  org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
                      .class);
      SessionTaskResponseConfiguration.sessionTaskAsyncHandler()
          .postProcessAfterInitialization(adapter, "mvc-test");
    }

    MvcResult pending() throws Exception {
      var result =
          mvc.perform(
                  post("/api/session-trace/tasks")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn();
      assertTrue(result.getRequest().isAsyncStarted());
      return result;
    }

    void complete(MvcResult pending) {
      ((SessionEventLedger.SearchRequest)
              pending.getRequest().getAttribute("jimu.session.search.request"))
          .completed()
          .accept(new byte[] {'{', '}'});
    }

    void released() throws Exception {
      assertEquals(65536, query.scratchLive());
      response.close();
      response.produced();
      assertEquals(65536, query.scratchLive());
      for (String name : List.of("view", "budget")) {
        var field = SessionTaskResponse.class.getDeclaredField(name);
        field.setAccessible(true);
        assertNull(field.get(response));
      }
    }
  }
}
