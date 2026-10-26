package io.github.lovexiaopeng.jimu.kernel.event.api;

/** 可靠事件原有名额：两个槽保留给结束或内容清理事件。 */
public final class ReliableEventPolicyV1 {
  public static final int ORDINARY_EVENTS = 254;
  public static final int RESERVED_EVENTS = 256;

  private ReliableEventPolicyV1() {}
}
