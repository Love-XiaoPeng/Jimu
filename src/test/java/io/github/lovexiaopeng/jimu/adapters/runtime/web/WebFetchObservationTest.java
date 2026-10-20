package io.github.lovexiaopeng.jimu.adapters.runtime.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Failure;
import io.github.lovexiaopeng.jimu.kernel.task.api.WebFetchObservation.Phase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

class WebFetchObservationTest {
  private static final String CANARY = "FICTIONAL_ONLY_SECRET_BODY_CANARY";

  @Test
  void actualExceptionTypesAndLastConfirmedPhaseNeverExportBodies() throws Exception {
    for (Phase failed :
        new Phase[] {Phase.DNS, Phase.CONNECT_TLS, Phase.HEADERS, Phase.BODY, Phase.PROJECTION}) {
      var events = new CopyOnWriteArrayList<WebFetchObservation>();
      IOException fault =
          switch (failed) {
            case DNS -> new UnknownHostException(CANARY);
            case CONNECT_TLS -> new SSLException(CANARY);
            case HEADERS -> new SocketTimeoutException(CANARY);
            default -> new IOException(CANARY);
          };
      var reader =
          new PublicWebReader(
              new PublicWebPolicy(Set.of("example.com")),
              host -> {
                if (failed == Phase.DNS) throw fault;
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
              },
              (uri, pinned, context, active, progress) -> {
                if (failed != Phase.CONNECT_TLS) progress.accept(failed);
                throw fault;
              });
      var error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  reader.fetch("https://example.com/", PublicWebReaderTest.context(), events::add));
      assertSame(fault, error.getCause());
      Failure expected =
          switch (failed) {
            case DNS -> Failure.DNS;
            case CONNECT_TLS -> Failure.TLS;
            case HEADERS -> Failure.TIMEOUT;
            default -> Failure.IO;
          };
      assertEquals(new WebFetchObservation(failed, expected), events.getLast());
      assertFalse(Payloads.json(events).contains(CANARY));
    }
  }

  @Test
  void admissionAndRedirectPolicyFailuresAreDistinguishableWithoutUrls() throws Exception {
    var events = new CopyOnWriteArrayList<WebFetchObservation>();
    var reader =
        new PublicWebReader(
            new PublicWebPolicy(Set.of("example.com")),
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")},
            (uri, pinned, context, active, progress) ->
                new PinnedHttpsTransport.Reply(302, "https://other.example/" + CANARY, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            reader.fetch(
                "http://example.com/" + CANARY, PublicWebReaderTest.context(), events::add));
    assertEquals(new WebFetchObservation(Phase.ADMISSION, Failure.POLICY), events.getLast());
    events.clear();
    assertThrows(
        IllegalArgumentException.class,
        () -> reader.fetch("https://example.com/", PublicWebReaderTest.context(), events::add));
    assertEquals(new WebFetchObservation(Phase.REDIRECT, Failure.POLICY), events.getLast());
    assertFalse(Payloads.json(events).contains(CANARY));
  }
}
