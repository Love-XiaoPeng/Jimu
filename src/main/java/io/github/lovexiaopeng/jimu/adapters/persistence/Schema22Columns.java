package io.github.lovexiaopeng.jimu.adapters.persistence;

/** schema22完整原列序；计账和事实读取不得裁剪或重排。 */
final class Schema22Columns {
  private Schema22Columns() {}

  static final String STEP =
      "tenant_id,id,task_id,attempt_id,ordinal,state,request_snapshot,request_digest,response_snapshot,response_digest,dispatch_id,dispatches,model_version,record_version,request_cleanup_state,retained_request_digest,directory_revision,directory_cwd,directory_binding_digest,session_upper_seq,session_context_digest,session_binding_digest";
  static final String TASK_ATTEMPT =
      "tenant_id,id,task_id,worker_id,fence,state,started_at,ended_at,record_version,quiescent";
  static final String INVOCATION =
      "tenant_id,id,task_id,step_id,attempt_id,ordinal,slot,provider_id,effect_intent_id,tool,plugin_version,plugin_digest,installation_epoch,input_snapshot,input_digest,state,output_snapshot,output_digest,projection_version,updated_at,record_version,todo_expected_revision,todo_applied_revision,todo_committed_attempt_id,todo_committed_fence,todo_event_seq,todo_committed_at";
  static final String STEP_SESSION_SCOPE =
      "tenant_id,step_id,caller_task_id,record_version,authority_digest,context_snapshot,context_digest,context_bytes,content_state";
  static final String INSTALLATION =
      "tenant_id,plugin_id,enabled,epoch,plugin_version,plugin_digest";
  static final String TASK_TODO_PROJECTION =
      "tenant_id,task_id,record_version,todo_version,binding_snapshot,binding_digest,revision,content_state,source_invocation_id,source_step_id,committed_attempt_id,committed_fence,updated_seq,pending_count,in_progress_count,completed_count,output_digest,updated_at,retain_until,reserved_bytes,retained_digests";
}
