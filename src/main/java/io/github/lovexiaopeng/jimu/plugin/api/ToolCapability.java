package io.github.lovexiaopeng.jimu.plugin.api;

/** 公开宿主能力名称；仅命名既有 ID，版本、摘要、描述和 Schema 仍从受检资源读取。 */
public final class ToolCapability {
  public static final String WEB_FETCH = "web_fetch";
  public static final String WEB_SEARCH = "web_search";
  public static final String SESSION_EVENT_READ = "session_event_read";
  public static final String SESSION_EVENT_SEARCH = "session_event_search";
  public static final String SESSION_EVENT_TRACE = "session_event_trace";
  public static final String SESSION_TRACE = "session_trace";
  public static final String SESSION_SEARCH = "session_search";
  public static final String WORKING_DIRECTORY = "working_directory";
  public static final String TODO_WRITE = "todo_write";
  public static final String ASK_USER_QUESTION = "ask_user_question";
  public static final String READ = "read";
  public static final String WRITE = "write";
  public static final String PRESENT = "present";
  public static final String COMPOSE_REPORT = "compose_report";
  public static final String EDIT = "edit";
  public static final String GLOB = "glob";
  public static final String GREP = "grep";
  public static final String READ_IMAGE = "read_image";

  public static final String LIST_MCP_RESOURCE_TEMPLATES = "list_mcp_resource_templates";

  public static final String LIST_MCP_RESOURCES = "list_mcp_resources";

  public static final String READ_MCP_RESOURCE = "read_mcp_resource";

  private ToolCapability() {}
}
