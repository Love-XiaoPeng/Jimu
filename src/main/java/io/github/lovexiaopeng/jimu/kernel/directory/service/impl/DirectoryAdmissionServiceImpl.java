package io.github.lovexiaopeng.jimu.kernel.directory.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryContentState;
import io.github.lovexiaopeng.jimu.kernel.directory.api.DirectoryErrorCode;
import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAdmission;
import io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryAdmissionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Preserve flag, full projection, content and historical model qualification order. */
public final class DirectoryAdmissionServiceImpl implements DirectoryAdmissionService {
  public void check(DirectoryAdmission records) {
    if (!records.enabled())
      throw new TaskFailure(403, DirectoryErrorCode.DIRECTORY_FEATURE_DISABLED);
    if (!DirectoryContentState.PRESENT.equals(records.projection().contentState()))
      throw new TaskFailure(410, DirectoryErrorCode.DIRECTORY_CONTENT_EXPIRED);
    records.requireDirectory(records.snapshot());
  }
}
