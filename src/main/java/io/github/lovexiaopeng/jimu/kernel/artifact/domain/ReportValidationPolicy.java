package io.github.lovexiaopeng.jimu.kernel.artifact.domain;

import io.github.lovexiaopeng.jimu.kernel.snapshot.api.SnapshotDigest;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.ReportContext;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Source;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.FilePort;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 报告结构与来源证明规则；摘要及逐来源解码由适配器提供，失败原样传播。 */
public final class ReportValidationPolicy {
  private static final int MAX_REPORT_BYTES = 1048576;
  private static final int MAX_SOURCES = 8;
  private static final int MIN_SOURCE_CODE_POINTS = 32;
  private static final List<String> SECTIONS = List.of("主题", "摘要", "结论", "来源链接", "生成时间", "局限");
  private static final Pattern HEADING =
      Pattern.compile("(?m)^#{1,6} (主题|摘要|结论|来源链接|生成时间|局限)[ \\t]*$");
  private static final Pattern URL =
      Pattern.compile("https?://[^\\s<>\\[\\]()]+", Pattern.CASE_INSENSITIVE);
  private static final String UNUSABLE_CONTENT =
      "(?s).*(sign in|log in|login|authentication required|access denied|captcha|search results|search snippet|error page|not found|service unavailable|something went wrong|登录|访问被拒绝|验证码|错误页面|页面不存在).*";

  private final SnapshotDigest digest;
  private final Function<String, FetchedSource> decode;

  public ReportValidationPolicy(SnapshotDigest digest, Function<String, FetchedSource> decode) {
    this.digest = digest;
    this.decode = decode;
  }

  /** 已解码的来源字段，不携带插件或JSON框架类型。 */
  public record FetchedSource(
      int httpStatus, String sourceUrl, String content, boolean truncated) {}

  public record Evidence(String sourceUrl, String body, boolean usable) {}

  /** 先校验文件，再按原顺序逐个校验摘要并解码；不预解码或转换后续来源异常。 */
  public void validate(FilePort.Snapshot file, ReportContext context) {
    byte[] bytes = file.content().getBytes(StandardCharsets.UTF_8);
    if (!file.filePath().endsWith(".md")
        || bytes.length == 0
        || bytes.length > MAX_REPORT_BYTES
        || bytes.length != file.size()
        || !digest.hash(file.content()).equals(file.sha256())
        || file.version() < 1) throw new TaskFailure(409, "REPORT_INVALID");
    Set<String> valid = new HashSet<>();
    boolean partial = false;
    for (var source : context.sources()) {
      var evidence = evidence(source);
      if (evidence.usable()) valid.add(evidence.sourceUrl());
      else partial = true;
    }
    if (valid.isEmpty()) throw new TaskFailure(409, "NO_EVIDENCE");
    if (valid.size() > MAX_SOURCES) throw new TaskFailure(409, "REPORT_SOURCE_LIMIT");
    var sections = new LinkedHashMap<String, String>();
    var matcher = HEADING.matcher(file.content());
    String last = null;
    int start = 0;
    while (matcher.find()) {
      if (last != null)
        sections.put(last, file.content().substring(start, matcher.start()).strip());
      last = matcher.group(1);
      if (sections.containsKey(last)) throw new TaskFailure(409, "REPORT_INVALID");
      start = matcher.end();
    }
    if (last != null) sections.put(last, file.content().substring(start).strip());
    if (sections.size() != 6
        || !sections.keySet().containsAll(SECTIONS)
        || sections.values().stream().anyMatch(String::isBlank)
        || !context.productionTime().toString().equals(sections.get("生成时间")))
      throw new TaskFailure(409, "REPORT_INVALID");
    var citations = links(file.content());
    if (citations.isEmpty()
        || !valid.containsAll(citations)
        || links(sections.get("结论")).isEmpty()
        || links(sections.get("来源链接")).isEmpty())
      throw new TaskFailure(409, "REPORT_CITATION_INVALID");
    if (partial && !sections.get("局限").contains("部分来源失败"))
      throw new TaskFailure(409, "REPORT_LIMITATION_REQUIRED");
  }

  /** 不可信前缀必须精确匹配；strip仅用于判定，返回正文保留原字节对应的字符串。 */
  public Evidence evidence(Source source) {
    if (!digest.hash(source.output()).equals(source.digest()))
      throw new TaskFailure(409, "SNAPSHOT_CORRUPT");
    var output = decode.apply(source.output());
    String prefix =
        "[UNTRUSTED_WEB_CONTENT public-text-v1]\nSource: " + output.sourceUrl() + "\n\n";
    String body =
        output.content().startsWith(prefix) ? output.content().substring(prefix.length()) : "";
    String content = body.strip();
    String lower = content.toLowerCase(Locale.ROOT);
    boolean usable =
        output.httpStatus() >= 200
            && output.httpStatus() < 300
            && !output.truncated()
            && content.codePointCount(0, content.length()) >= MIN_SOURCE_CODE_POINTS
            && !lower.matches(UNUSABLE_CONTENT);
    return new Evidence(output.sourceUrl(), body, usable);
  }

  private Set<String> links(String content) {
    var result = new HashSet<String>();
    var matcher = URL.matcher(content);
    while (matcher.find()) result.add(matcher.group());
    return result;
  }
}
