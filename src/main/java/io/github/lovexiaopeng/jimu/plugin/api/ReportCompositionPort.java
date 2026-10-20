package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/**
 * Typed report assembly; source records, time, operation identity and renderer belong to the host.
 */
public interface ReportCompositionPort {
  record Segment(
      String kind,
      String text,
      String protocol,
      String identifier,
      String sourceRef,
      String evidence) {}

  record Draft(
      int version,
      String subject,
      List<Segment> summary,
      List<Segment> conclusions,
      List<String> limitations) {}

  /** Evidence offsets and lengths count UTF-8 bytes in the source body, excluding its wrapper. */
  record Binding(
      String invocationId,
      String outputDigest,
      String sourceUrl,
      long evidenceOffset,
      long evidenceLength,
      String quoteSha256) {}

  record Receipt(
      int version,
      String rendererVersion,
      String draftSha256,
      String reportTime,
      List<Binding> bindings,
      FilePort.Written file) {}

  Receipt compose(String filePath, Draft draft);
}
