package io.github.lovexiaopeng.jimu.plugin.api;

/** 宿主在原槽、当前权限和冻结图证明通过后提供追溯结果。 */
@FunctionalInterface
public interface SessionLineagePort {
  String trace(String input, ExecutionContext context);
}
