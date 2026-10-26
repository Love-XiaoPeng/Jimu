package io.github.lovexiaopeng.jimu.kernel.event.api;

/** 既有 SSE 容量和调度默认值；本轮保持固定，不启用推荐配置名或扩大并发。 */
public final class EventFeedPolicyV1 {
  public static final int SUBSCRIPTIONS = 32;
  public static final int PER_ACTOR_TASK = 2;
  public static final int BATCH = 64;
  public static final int POLL_MILLIS = 100;
  public static final int INITIAL_DELAY_MILLIS = 50;
  public static final int LIFETIME_SECONDS = 25;
  public static final long EMITTER_TIMEOUT_MILLIS = 30000L;

  private EventFeedPolicyV1() {}
}
