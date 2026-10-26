package io.github.lovexiaopeng.jimu.kernel.budget.api;

/** 本聚合既有数据库及公开字符值；保持 String 边界的显式值，不使用 ordinal、默认枚举编码或未知值回退。 */
public final class ReservationCategory {
  public static final String DIRECTORY_STORAGE = "DIRECTORY_STORAGE";
  public static final String MEDIA_STORAGE = "MEDIA_STORAGE";
  public static final String MODEL = "MODEL";
  public static final String MODEL_COST = "MODEL_COST";
  public static final String QUESTION_STORAGE = "QUESTION_STORAGE";
  public static final String QUEUE = "QUEUE";
  public static final String SESSION_HISTORY_STORAGE = "SESSION_HISTORY_STORAGE";
  public static final String STORAGE = "STORAGE";
  public static final String TODO_STORAGE = "TODO_STORAGE";
  public static final String TOOL = "TOOL";

  public static final String RUNNING = "RUNNING";

  public static final String MEDIA_CHECKPOINT = "MEDIA_CHECKPOINT";

  public static final String MEDIA_INPUT = "MEDIA_INPUT";

  public static final String MCP_RESULT = "MCP_RESULT";

  public static final String SEARCH_STORAGE = "SEARCH_STORAGE";

  public static final String QUESTION_TASK = "QUESTION_TASK";

  private ReservationCategory() {}
}
