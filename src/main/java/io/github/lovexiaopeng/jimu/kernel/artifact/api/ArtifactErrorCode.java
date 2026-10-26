package io.github.lovexiaopeng.jimu.kernel.artifact.api;

/** 既有公开失败码的语义归属；保持原字符值、HTTP 映射和失败次序。 */
public final class ArtifactErrorCode {
  public static final String ARTIFACT_EXPIRED = "ARTIFACT_EXPIRED";
  public static final String ARTIFACT_MISMATCH = "ARTIFACT_MISMATCH";
  public static final String ARTIFACT_UNVERIFIED = "ARTIFACT_UNVERIFIED";
  public static final String NO_EVIDENCE = "NO_EVIDENCE";
  public static final String PRESENT_COMMIT_REQUIRED = "PRESENT_COMMIT_REQUIRED";
  public static final String REPORT_ALREADY_STAGED = "REPORT_ALREADY_STAGED";
  public static final String REPORT_CITATION_INVALID = "REPORT_CITATION_INVALID";
  public static final String REPORT_COMPOSITION_INVALID = "REPORT_COMPOSITION_INVALID";
  public static final String REPORT_COMPOSITION_REQUIRED = "REPORT_COMPOSITION_REQUIRED";
  public static final String REPORT_DRAFT_ = "REPORT_DRAFT_";
  public static final String REPORT_DRAFT_ENDPOINT_INVALID = "REPORT_DRAFT_ENDPOINT_INVALID";
  public static final String REPORT_DRAFT_INVALID = "REPORT_DRAFT_INVALID";
  public static final String REPORT_DRAFT_KIND_INVALID = "REPORT_DRAFT_KIND_INVALID";
  public static final String REPORT_IMAGE_SOURCE_INVALID = "REPORT_IMAGE_SOURCE_INVALID";
  public static final String REPORT_INVALID = "REPORT_INVALID";
  public static final String REPORT_LIMITATION_REQUIRED = "REPORT_LIMITATION_REQUIRED";
  public static final String REPORT_METADATA_MISSING = "REPORT_METADATA_MISSING";
  public static final String REPORT_NOT_PUBLISHED = "REPORT_NOT_PUBLISHED";
  public static final String REPORT_POLICY_REQUIRED = "REPORT_POLICY_REQUIRED";
  public static final String REPORT_SOURCE_LIMIT = "REPORT_SOURCE_LIMIT";
  public static final String UNRESOLVED_FACTS = "UNRESOLVED_FACTS";

  public static final String ARTIFACT_UNAVAILABLE = "ARTIFACT_UNAVAILABLE";

  private ArtifactErrorCode() {}
}
