package io.github.lovexiaopeng.jimu.plugin.api;

import java.util.List;

/** Version 2 report drafting distinguishes source quotes from image interpretations. */
public interface ImageReportPort {
  record Segment(
      String sourceType,
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

  sealed interface Binding permits WebBinding, ImageBinding {}

  record WebBinding(
      String type,
      String invocationId,
      String outputDigest,
      String sourceUrl,
      long evidenceOffset,
      long evidenceLength,
      String quoteSha256)
      implements Binding {}

  record ImageBinding(
      String type,
      String invocationId,
      String outputDigest,
      String sourceRef,
      String inputId,
      String sourceSha256,
      String imageId,
      String imageSha256,
      String stepId,
      String dispatchId,
      String configDigest,
      String requestBodyDigest,
      String responseDigest,
      String textSha256)
      implements Binding {}

  record Receipt(
      int version,
      String rendererVersion,
      String draftSha256,
      String reportTime,
      List<Binding> bindings,
      FilePort.Written file) {}

  Receipt compose(String filePath, Draft draft);
}
