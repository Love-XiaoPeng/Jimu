package io.github.lovexiaopeng.jimu.plugin.api;

public record ToolDescriptor(
    String pluginId,
    String version,
    String capability,
    String inputSchema,
    String outputSchema,
    String outputSha256,
    String entryClass) {}
