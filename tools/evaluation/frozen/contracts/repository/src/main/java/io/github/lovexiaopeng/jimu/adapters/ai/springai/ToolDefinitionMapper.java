package io.github.lovexiaopeng.jimu.adapters.ai.springai;

import io.github.lovexiaopeng.jimu.plugin.api.ToolDescriptor;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Discovery projection only; no second tool loop or direct plugin callback. */
public final class ToolDefinitionMapper {
  public ToolDefinition map(ToolDescriptor descriptor, String inputSchema) {
    return ToolDefinition.builder()
        .name(descriptor.capability())
        .description("Fetch from the configured origin")
        .inputSchema(inputSchema)
        .build();
  }
}
