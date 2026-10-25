package io.github.lovexiaopeng.jimu.api.readiness.vo;

/** 就绪接口的既有响应字段，保持角色、状态与插件数的协议形状。 */
public record ReadinessResponse(String role, String status, int plugins) {}
