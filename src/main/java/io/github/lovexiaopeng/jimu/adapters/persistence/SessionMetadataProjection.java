package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting.Table;
import java.util.Arrays;
import java.util.List;

/** 原V1–V23 SELECT的列型；静态描述不读取行内bytes，不为无业务上界的表虚构行数。 */
final class SessionMetadataProjection {
  record Column(String name, char kind, int points, boolean nullable) {
    long retainedBytes() {
      // Map entry/label/value header plus worst UTF16 string or boxed scalar/date.
      return 256L + (kind == 'S' ? 64L + 4L * points : 128L);
    }
  }

  final List<Column> columns;
  final java.util.Set<String> names;
  final int maximumRows;
  final long retainedBytes;

  private SessionMetadataProjection(String specification, int maximumRows) {
    this.maximumRows = maximumRows;
    columns =
        Arrays.stream(specification.split(",")).map(SessionMetadataProjection::column).toList();
    names =
        columns.stream().map(Column::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    // Two bounded row workspaces cover driver getter/materialization overlap; the
    // QueryContext.Result cursor owner is separately additive, including all scanned rows.
    retainedBytes = 2048 + 2 * columns.stream().mapToLong(Column::retainedBytes).sum();
  }

  private static Column column(String s) {
    int colon = s.indexOf(':');
    String type = s.substring(colon + 1).replace("?", "");
    return new Column(
        s.substring(0, colon),
        type.charAt(0),
        type.charAt(0) == 'S' ? Integer.parseInt(type.substring(1)) : 0,
        s.endsWith("?"));
  }

  static SessionMetadataProjection forTable(Table table) {
    return PROJECTIONS.get(table);
  }

  private static final java.util.Map<Table, SessionMetadataProjection> PROJECTIONS = projections();

  private static java.util.Map<Table, SessionMetadataProjection> projections() {
    var result = new java.util.EnumMap<Table, SessionMetadataProjection>(Table.class);
    result.put(
        Table.SESSION_PUBLIC_EVENT,
        new SessionMetadataProjection(
            "tenant_id:S64,task_id:S36,seq:L,record_version:I,event_version:I,fact_key:S96,type:S32,occurred_at:D,event_digest:S64,event_bytes:I,source_digest:S64,source_step_id:S36?,source_invocation_id:S36?,committed_attempt_id:S36?,committed_fence:L?,reliable_event_seq:L,content_state:S16",
            128));
    result.put(
        Table.INVOCATION_SESSION_READ,
        new SessionMetadataProjection(
            "tenant_id:S64,invocation_id:S36,task_id:S36,step_id:S36,record_version:I,binding_digest:S64,input_digest:S64,upper_seq:L,target_seq:L,before_count:I,after_count:I,outcome:S16,failure_code:S64?,window_start:L?,window_end:L?,target_digest:S64?,output_digest:S64?,output_bytes:I?,receipt_seq:L?,committed_attempt_id:S36?,committed_fence:L?,committed_at:D?,content_state:S16,relation_version:I,read_sources_digest:S64?,read_sources_bytes:I?",
            0));
    result.put(
        Table.INVOCATION_SESSION_SEARCH,
        new SessionMetadataProjection(
            "tenant_id:S64,invocation_id:S36,task_id:S36,step_id:S36,record_version:I,binding_digest:S64,input_digest:S64,upper_seq:L,normalized_query_digest:S64,normalized_query_bytes:I,outcome:S16,failure_code:S64?,scan_digest:S64?,scan_rows:I?,scan_event_bytes:I?,output_digest:S64?,output_bytes:I?,receipt_event_seq:L?,committed_attempt_id:S36?,committed_fence:L?,committed_at:D?,content_state:S16",
            0));
    result.put(
        Table.INVOCATION_SESSION_TRACE,
        new SessionMetadataProjection(
            "tenant_id:S64,invocation_id:S36,task_id:S36,step_id:S36,record_version:I,binding_digest:S64,input_digest:S64,upper_seq:L,target_seq:L,relation_policy:S32,outcome:S16,failure_code:S64?,scan_digest:S64?,relations_digest:S64?,target_digest:S64?,scan_rows:I?,scan_event_bytes:I?,relation_edges:I?,output_digest:S64?,output_bytes:I?,receipt_event_seq:L?,committed_attempt_id:S36?,committed_fence:L?,committed_at:D?,content_state:S16",
            0));
    result.put(
        Table.TASK_SESSION_SCOPE,
        new SessionMetadataProjection(
            "tenant_id:S64,caller_task_id:S36,record_version:I,authority_version:I,owner_actor_id:S64,caller_binding_digest:S64,caller_tools_digest:S64,authority_digest:S64,authority_bytes:I,candidate_count:I,grant_state:S16,created_at:D,retain_until:D,revoked_at:D?,invalidated_at:D?,next_validation_at:D,content_state:S16",
            1));
    result.put(
        Table.TASK_SESSION_SCOPE_TARGET,
        new SessionMetadataProjection(
            "tenant_id:S64,caller_task_id:S36,ordinal:I,record_version:I,target_digest:S64,target_bytes:I,content_state:S16",
            8));
    result.put(
        Table.STEP_SESSION_SCOPE,
        new SessionMetadataProjection(
            "tenant_id:S64,step_id:S36,caller_task_id:S36,record_version:I,authority_digest:S64,context_digest:S64,context_bytes:I,content_state:S16",
            0));
    result.put(
        Table.INVOCATION_SESSION_SCOPE_QUERY,
        new SessionMetadataProjection(
            "tenant_id:S64,invocation_id:S36,caller_task_id:S36,step_id:S36,record_version:I,operation:S8,binding_digest:S64,input_digest:S64,authority_digest:S64,context_digest:S64,normalized_digest:S64,normalized_bytes:I,outcome:S16,failure_code:S64?,scan_digest:S64?,scan_rows:I?,scan_event_bytes:I?,read_refs_digest:S64?,read_refs_bytes:I?,output_digest:S64?,output_bytes:I?,receipt_event_seq:L?,native_read_seq:L?,committed_attempt_id:S36?,committed_fence:L?,committed_at:D?,content_state:S16",
            0));
    result.put(
        Table.TASK_ATTEMPT,
        new SessionMetadataProjection(
            "tenant_id:S64,id:S36,task_id:S36,worker_id:S36,fence:L,state:S24,started_at:D,ended_at:D?,record_version:I,quiescent:B",
            0));
    result.put(
        Table.STEP,
        new SessionMetadataProjection(
            "tenant_id:S64,id:S36,task_id:S36,attempt_id:S36,ordinal:I,state:S24,request_digest:S64,response_digest:S64?,dispatch_id:S36?,dispatches:I,model_version:S160,record_version:I,request_cleanup_state:S24,retained_request_digest:S64?,directory_revision:L?,directory_cwd:S512?,directory_binding_digest:S64?,session_upper_seq:L?,session_context_digest:S64?,session_binding_digest:S64?",
            0));
    result.put(
        Table.INVOCATION,
        new SessionMetadataProjection(
            "tenant_id:S64,id:S36,task_id:S36,step_id:S36,attempt_id:S36,ordinal:I,slot:S160,provider_id:S128,effect_intent_id:S36,tool:S64,plugin_version:S32,plugin_digest:S64,installation_epoch:L,input_digest:S64,state:S32,output_digest:S64?,projection_version:I,updated_at:D,record_version:I,todo_expected_revision:L?,todo_applied_revision:L?,todo_committed_attempt_id:S36?,todo_committed_fence:L?,todo_event_seq:L?,todo_committed_at:D?",
            0));
    result.put(
        Table.MODEL_DISPATCH,
        new SessionMetadataProjection(
            "tenant_id:S64,id:S36,task_id:S36,step_id:S36,config_digest:S64,request_digest:S64,state:S24,code:S64?,started_at:D,ended_at:D?,input_tokens:L?,output_tokens:L?,total_tokens:L?,price_version:S128?,currency:S3?,reserved_cost:L?,actual_cost:L?,cost_quality:S16",
            0));
    result.put(
        Table.RESERVATION,
        new SessionMetadataProjection(
            "tenant_id:S64,id:S80,task_id:S36,dispatch_id:S36?,category:S24,amount:L,actual:L?,usage_quality:S16,cost_quality:S16,state:S24,record_version:I",
            0));
    result.put(
        Table.EVENT,
        new SessionMetadataProjection(
            "tenant_id:S64,task_id:S36,seq:L,event_id:S64,fact_id:S128,type:S64,code:S64?,status:S32,artifact_id:S36?,occurred_at:D,payload_version:I",
            0));
    result.put(
        Table.INVOCATION_SESSION_LINEAGE_TRACE,
        new SessionMetadataProjection(
            "tenant_id:S64,invocation_id:S36,caller_task_id:S36,step_id:S36,record_version:I,binding_digest:S64,input_digest:S64,authority_digest:S64,graph_digest:S64,context_digest:S64,normalized_digest:S64,normalized_bytes:I,outcome:S16,failure_code:S64?,scan_digest:S64?,node_count:I?,edge_count:I?,proof_reads:I?,proof_bytes:I?,output_digest:S64?,output_bytes:I?,receipt_event_seq:L?,committed_attempt_id:S36?,committed_fence:L?,committed_at:D?,content_state:S16",
            0));
    return java.util.Map.copyOf(result);
  }
}
