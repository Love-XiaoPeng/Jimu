import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Architecture-only wire/transport feasibility probe, not a Jimu runtime implementation. */
class ProtocolProbe {
  static final List<String> trace = Collections.synchronizedList(new ArrayList<>());
  static final AtomicInteger foreign = new AtomicInteger(), proxied = new AtomicInteger();
  static final String token = "fixture-only-disposable";
  static URI endpoint;
  static HttpClient client;
  static HttpRequest request(String body) {
    return HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
      .header("Authorization", "Bearer " + token)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json, text/event-stream")
      .header("MCP-Protocol-Version", "2025-06-18")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
  }
  static HttpResponse<String> send(String body) throws Exception {
    trace.add("CLIENT " + body);
    var r = client.send(request(body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    trace.add("SERVER " + r.statusCode() + " " + r.body());
    return r;
  }
  static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
    trace.add("PASS " + description);
  }
  static void allowed(URI candidate) {
    if (!candidate.equals(endpoint) || !"127.0.0.1".equals(candidate.getHost())
        || candidate.getUserInfo() != null || candidate.getRawQuery() != null || candidate.getFragment() != null)
      throw new IllegalArgumentException("MCP_ENDPOINT_DENIED");
  }
  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]);
    ExecutorService handlers = Executors.newCachedThreadPool();
    var service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    CountDownLatch slowStarted = new CountDownLatch(1), cancelled = new CountDownLatch(1);
    other.createContext("/", e -> { foreign.incrementAndGet(); e.sendResponseHeaders(204,-1); e.close(); });
    service.setExecutor(handlers);
    service.createContext("/mcp", e -> {
      String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      String response;
      int code = 200;
      String type = "application/json";
      if (!Objects.equals(e.getRequestHeaders().getFirst("Authorization"), "Bearer " + token)) {
        code=401; response="";
      } else if (body.contains("notifications/cancelled")) {
        cancelled.countDown(); code=202; response="";
      } else if (body.contains("notifications/initialized")) {
        code=202; response="";
      } else if (body.contains("initialize")) {
        response="{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"resources\":{}},\"serverInfo\":{\"name\":\"jimu-controlled-resource\",\"version\":\"0.1.0\"}}}";
      } else if (body.contains("resources/templates/list")) {
        response="{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"resourceTemplates\":[{\"uriTemplate\":\"jimu-fixture://docs/{name}\",\"name\":\"docs\"}]}}";
      } else if (body.contains("resources/list")) {
        response=body.contains("page2")
          ? "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"resources\":[{\"uri\":\"jimu-fixture://docs/b\",\"name\":\"b\"}]}}"
          : "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"resources\":[{\"uri\":\"jimu-fixture://docs/a\",\"name\":\"a\"}],\"nextCursor\":\"page2\"}}";
      } else if (body.contains("slow")) {
        slowStarted.countDown();
        try { cancelled.await(3, TimeUnit.SECONDS); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
        response="{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"contents\":[]}}";
      } else if (body.contains("missing")) {
        response="{\"jsonrpc\":\"2.0\",\"id\":6,\"error\":{\"code\":-32002,\"message\":\"Resource not found\"}}";
      } else if (body.contains("redirect")) {
        code=302; response="";
        e.getResponseHeaders().set("Location", "http://127.0.0.1:"+other.getAddress().getPort()+"/leak");
      } else if (body.contains("sse")) {
        type="text/event-stream";
        response="event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"contents\":[{\"uri\":\"jimu-fixture://docs/sse\",\"text\":\"SSE text\"}]}}\n\n";
      } else {
        response="{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"contents\":[{\"uri\":\"jimu-fixture://docs/a\",\"mimeType\":\"text/plain\",\"text\":\"controlled MCP content\"}]}}";
      }
      trace.add("FIXTURE method="+e.getRequestMethod()+" accept="+e.getRequestHeaders().getFirst("Accept")+" protocol="+e.getRequestHeaders().getFirst("MCP-Protocol-Version"));
      byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
      e.getResponseHeaders().set("Content-Type",type);
      try {
        e.sendResponseHeaders(code,bytes.length==0 ? -1 : bytes.length);
        if(bytes.length>0)e.getResponseBody().write(bytes);
      } catch (java.io.IOException disconnected) { trace.add("FIXTURE closed cancelled response"); }
      finally { e.close(); }
    });
    other.start(); service.start();
    endpoint=URI.create("http://127.0.0.1:"+service.getAddress().getPort()+"/mcp");
    ProxySelector original=ProxySelector.getDefault();
    ProxySelector.setDefault(new ProxySelector(){
      public List<Proxy> select(URI u){proxied.incrementAndGet(); return List.of(Proxy.NO_PROXY);}
      public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}
    });
    try {
      client=HttpClient.newBuilder().proxy(new ProxySelector(){
        public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}
        public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}
      }).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).build();
      trace.add("ENV java="+System.getProperty("java.version")+" os="+System.getProperty("os.name")+" endpoint="+endpoint);
      allowed(endpoint);
      check(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"jimu-probe\",\"version\":\"0.1.0\"}}}").body().contains("\"resources\":{}"),"initialize resource capability");
      check(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").statusCode()==202,"initialized notification HTTP 202");
      check(send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/templates/list\",\"params\":{}}").body().contains("uriTemplate"),"templates protocol");
      check(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\",\"params\":{}}").body().contains("page2"),"upstream page one cursor");
      check(send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\",\"params\":{\"cursor\":\"page2\"}}").body().contains("docs/b"),"upstream page two");
      check(send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":\"jimu-fixture://docs/a\"}}").body().contains("controlled MCP content"),"read actual protocol");
      check(send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/read\",\"params\":{\"uri\":\"jimu-fixture://docs/missing\"}}").body().contains("-32002"),"resource not found error");
      var sse=send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/read\",\"params\":{\"uri\":\"jimu-fixture://docs/sse\"}}");
      check(sse.headers().firstValue("Content-Type").orElse("").equals("text/event-stream") && sse.body().contains("data: {\"jsonrpc\":\"2.0\",\"id\":7"),"POST SSE wire path");
      check(send("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"resources/read\",\"params\":{\"uri\":\"jimu-fixture://docs/redirect\"}}").statusCode()==302,"redirect not followed");
      int denied=0;
      for (String uri : List.of("http://localhost:"+service.getAddress().getPort()+"/mcp","http://127.0.0.1:"+other.getAddress().getPort()+"/mcp","http://127.0.0.1:"+service.getAddress().getPort()+"/other","https://example.invalid/mcp")) {
        try {allowed(URI.create(uri));}catch(IllegalArgumentException rejected){denied++;}
      }
      check(denied==4,"exact endpoint rejects DNS alias other port other path public endpoint before I/O");
      var pending=client.sendAsync(request("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"jimu-fixture://docs/slow\"}}"),HttpResponse.BodyHandlers.ofString());
      check(slowStarted.await(2,TimeUnit.SECONDS),"slow request reached dedicated fixture");
      check(pending.cancel(true),"client cancellation accepted");
      check(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":8,\"reason\":\"task cancelled\"}}").statusCode()==202,"best effort cancel notification");
      boolean cancelledResult=false;
      try { pending.join(); }
      catch (CancellationException | CompletionException expected) {
        cancelledResult=true;
        trace.add("CANCEL completion="+expected.getClass().getName()+" cause="+(expected.getCause()==null ? "none" : expected.getCause().getClass().getName()));
      }
      check(cancelledResult,"cancelled future cannot yield success");
      check(foreign.get()==0 && proxied.get()==0,"zero redirect-target calls and zero default proxy calls");
    } finally {
      if(client!=null)client.close();
      ProxySelector.setDefault(original);
      service.stop(0); other.stop(0); handlers.shutdownNow();
      check(handlers.awaitTermination(3,TimeUnit.SECONDS),"owned fixture threads terminated");
      check(service.getAddress().getAddress().isLoopbackAddress(),"fixture bound literal loopback");
      Files.write(out,trace,StandardCharsets.UTF_8);
    }
    System.out.println("PASS architecture transport probe; "+trace.stream().filter(s->s.startsWith("PASS ")).count()+" assertions; no Jimu runtime, DB or production isolation validation");
  }
}
