package io.github.lovexiaopeng.jimu.adapters.runtime.contract;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportFile;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Source;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Binding;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Draft;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Receipt;
import io.github.lovexiaopeng.jimu.plugin.api.ReportCompositionPort.Segment;
import io.github.lovexiaopeng.jimu.plugins.builtin.workspace.ComposeReportPlugin;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure v1 assembly. Never repairs a raw report or treats a draft as trusted Markdown. */
public final class ReportComposer {
  public static final String RENDERER = "source-bound-report-v1";

  /** Raw invocation snapshots use the existing MySQL TEXT byte capacity. */
  public static final int INPUT_MAX_BYTES = 65535;

  private static final ObjectMapper STRICT =
      new ObjectMapper()
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Pattern URL =
      Pattern.compile("https?://[^\\s<>\\[\\]()]+", Pattern.CASE_INSENSITIVE);
  private final ReportPolicy policy = new ReportPolicy();

  public record Composed(
      String markdown, String draftSha256, String reportTime, List<Binding> bindings) {
    public Receipt receipt(FilePort.Written file) {
      return new Receipt(1, RENDERER, draftSha256, reportTime, bindings, file);
    }
  }

  public static JsonNode parse(String raw) throws IOException {
    if (raw.getBytes(StandardCharsets.UTF_8).length > INPUT_MAX_BYTES)
      throw new IllegalArgumentException("Report input too large");
    var value = STRICT.readTree(raw);
    if (value == null || !value.isObject())
      throw new IllegalArgumentException("Invalid report object");
    return value;
  }

  public String instruction(ReportContext context) {
    return policy.currentInstruction(context)
        + " Use compose_report, renderer source-bound-report-v1, instead of write. First web_fetch"
        + " sources and read the target file, then call compose_report with file_path and draft."
        + " Draft version is 1; subject is plain text; summary and conclusions are arrays of 1-8"
        + " segments; limitations is an array of at most 4 plain strings. Each segment contains"
        + " sourceRef (exact fetched sourceUrl) and evidence (an exact source body quote)."
        + " A text segment has kind=text and text without any HTTP(S) URL. An endpoint segment has"
        + " kind=endpoint, protocol=http or https and identifier=host/path without protocol; its"
        + " complete protocol+identifier must occur in the quoted source. Do not supply Markdown,"
        + " host time, source bodies or receipt fields. The host generates all six sections,"
        + " citations and time. Finally present the composed file. Invalid drafts fail as a whole.";
  }

  public Composed compose(String path, Draft draft, ReportContext context) {
    require(draft.version() == 1, "VERSION_UNSUPPORTED");
    String subject = plain(draft.subject(), 120);
    require(
        draft.summary() != null && !draft.summary().isEmpty() && draft.summary().size() <= 8,
        "BLOCKS_INVALID");
    require(
        draft.conclusions() != null
            && !draft.conclusions().isEmpty()
            && draft.conclusions().size() <= 8,
        "BLOCKS_INVALID");
    require(draft.limitations() != null && draft.limitations().size() <= 4, "BLOCKS_INVALID");
    require(
        Payloads.json(draft).getBytes(StandardCharsets.UTF_8).length <= INPUT_MAX_BYTES,
        "TOO_LARGE");
    Map<String, Source> sources = new LinkedHashMap<>();
    boolean partial = false;
    for (Source source : context.sources()) {
      var evidence = policy.evidence(source);
      if (!evidence.usable()) {
        partial = true;
        continue;
      }
      Source previous = sources.putIfAbsent(evidence.sourceUrl(), source);
      require(previous == null || previous.digest().equals(source.digest()), "SOURCE_AMBIGUOUS");
    }
    var bindings = new ArrayList<Binding>();
    Set<String> used = new LinkedHashSet<>();
    String summary = blocks(draft.summary(), sources, bindings, used);
    String conclusions = blocks(draft.conclusions(), sources, bindings, used);
    var links = new StringBuilder();
    for (String url : used) links.append("- [来源](").append(url).append(")。\n");
    var limits = new StringBuilder("结构化内容按已保存资料组装；端点保留协议与标识，不作为已抓取来源。\n");
    for (String limit : draft.limitations())
      limits.append("- ").append(plain(limit, 400)).append('\n');
    if (partial) limits.append("部分来源失败。\n");
    if (sources.size() > used.size()) limits.append("部分可用来源未用于本报告。\n");
    String markdown =
        "## 主题\n"
            + subject
            + "\n\n## 摘要\n"
            + summary
            + "\n## 结论\n"
            + conclusions
            + "\n## 来源链接\n"
            + links
            + "\n## 生成时间\n"
            + context.productionTime()
            + "\n\n## 局限\n"
            + limits;
    byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
    policy.validate(
        new FilePort.Snapshot(path, 1, Payloads.hash(bytes), bytes.length, markdown), context);
    Set<String> rendered = new LinkedHashSet<>();
    var matcher = URL.matcher(markdown);
    while (matcher.find()) rendered.add(matcher.group());
    require(rendered.equals(used), "URL_SET_MISMATCH");
    return new Composed(
        markdown,
        Payloads.hash(Payloads.json(draft)),
        context.productionTime().toString(),
        List.copyOf(bindings));
  }

  /** Reconstruct from the confirmed invocation, never from a model-supplied receipt. */
  public boolean matches(String rawInput, String output, ReportFile file, ReportContext context) {
    try {
      var input = parse(rawInput);
      var contract = new WorkspaceContracts().load("compose_report");
      contract.in().validate(input);
      contract.out().validate(parse(output));
      var call = Payloads.read(input.toString(), ComposeReportPlugin.Input.class);
      var receipt = Payloads.read(output, Receipt.class);
      if (!file.path().equals(call.file_path()) || !file.path().equals(receipt.file().filePath()))
        return false;
      var expected = compose(call.file_path(), call.draft(), context);
      return receipt.equals(
              expected.receipt(
                  new FilePort.Written(file.path(), file.version(), file.sha256(), file.size())))
          && file.sha256().equals(Payloads.hash(expected.markdown()))
          && file.size() == expected.markdown().getBytes(StandardCharsets.UTF_8).length;
    } catch (IOException | IllegalArgumentException e) {
      throw new TaskFailure(409, "REPORT_COMPOSITION_INVALID");
    }
  }

  private String blocks(
      List<Segment> segments,
      Map<String, Source> sources,
      List<Binding> bindings,
      Set<String> used) {
    var result = new StringBuilder();
    for (var segment : segments) {
      require(segment != null, "SHAPE_INVALID");
      Source source = sources.get(segment.sourceRef());
      require(source != null, "SOURCE_UNKNOWN");
      var saved = policy.evidence(source);
      String quote = segment.evidence(), body = saved.body();
      require(quote != null && !quote.isBlank() && quote.length() <= 800, "EVIDENCE_MISMATCH");
      int offset = body.indexOf(quote);
      require(offset >= 0, "EVIDENCE_MISMATCH");
      String text;
      if ("text".equals(segment.kind())) {
        require(segment.protocol() == null && segment.identifier() == null, "SHAPE_INVALID");
        text = plain(segment.text(), 600);
      } else if ("endpoint".equals(segment.kind())) {
        require(
            segment.text() == null
                && segment.protocol() != null
                && Set.of("http", "https").contains(segment.protocol()),
            "ENDPOINT_INVALID");
        String identifier = segment.identifier();
        require(
            identifier != null
                && !identifier.isBlank()
                && identifier.length() <= 300
                && identifier
                    .codePoints()
                    .noneMatch(
                        c ->
                            Character.isISOControl(c)
                                || Character.isWhitespace(c)
                                || Character.isSpaceChar(c)
                                || Character.getType(c) == Character.FORMAT)
                && !Pattern.compile("[\\s`\\\\<>\\[\\]()&]").matcher(identifier).find(),
            "ENDPOINT_INVALID");
        URI endpoint;
        try {
          endpoint = URI.create(segment.protocol() + "://" + identifier);
        } catch (IllegalArgumentException e) {
          throw new TaskFailure(409, "REPORT_DRAFT_ENDPOINT_INVALID");
        }
        require(
            endpoint.getHost() != null
                && endpoint.getRawUserInfo() == null
                && endpoint.getRawQuery() == null
                && endpoint.getRawFragment() == null,
            "ENDPOINT_INVALID");
        offset = supportedQuote(body, quote, endpoint.toString());
        require(offset >= 0, "ENDPOINT_UNSUPPORTED");
        text = "端点标识 `" + identifier + "`（协议 `" + segment.protocol() + "`）";
      } else throw new TaskFailure(409, "REPORT_DRAFT_KIND_INVALID");
      bindings.add(
          new Binding(
              source.invocationId(),
              source.digest(),
              saved.sourceUrl(),
              body.substring(0, offset).getBytes(StandardCharsets.UTF_8).length,
              quote.getBytes(StandardCharsets.UTF_8).length,
              Payloads.hash(quote)));
      used.add(saved.sourceUrl());
      result.append("- ").append(text).append(" [来源](").append(saved.sourceUrl()).append(")。\n");
    }
    return result.toString();
  }

  private static int supportedQuote(String body, String quote, String endpoint) {
    for (int offset = body.indexOf(quote); offset >= 0; offset = body.indexOf(quote, offset + 1)) {
      for (int start = quote.indexOf(endpoint);
          start >= 0;
          start = quote.indexOf(endpoint, start + 1)) {
        int begin = offset + start, end = begin + endpoint.length();
        if ((begin == 0 || boundary(body.charAt(begin - 1)))
            && (end == body.length() || boundary(body.charAt(end)))) return offset;
      }
    }
    return -1;
  }

  private static boolean boundary(char c) {
    return Character.isWhitespace(c) || "（()）【[]】<>\"'`：，。；！？".indexOf(c) >= 0;
  }

  private static String plain(String value, int max) {
    require(
        value != null
            && !value.isBlank()
            && value.length() <= max
            && value
                .codePoints()
                .noneMatch(
                    c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT),
        "TEXT_INVALID");
    require(!URL.matcher(value).find(), "RAW_URL_FORBIDDEN");
    String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    for (String control : List.of("\\", "`", "*", "_", "[", "]", "(", ")", "#", "!", "|"))
      escaped = escaped.replace(control, "\\" + control);
    return escaped;
  }

  private static void require(boolean condition, String code) {
    if (!condition) throw new TaskFailure(409, "REPORT_DRAFT_" + code);
  }
}
