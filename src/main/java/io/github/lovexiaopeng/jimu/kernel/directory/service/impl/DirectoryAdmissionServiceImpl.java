package io.github.lovexiaopeng.jimu.kernel.directory.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAdmission;
import io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryAdmissionService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Preserve flag, full projection, content and historical model qualification order. */
public final class DirectoryAdmissionServiceImpl implements DirectoryAdmissionService {
  public void check(DirectoryAdmission records) {
    if (!records.enabled()) throw new TaskFailure(403, "DIRECTORY_FEATURE_DISABLED");
    if (!"PRESENT".equals(records.projection().contentState()))
      throw new TaskFailure(410, "DIRECTORY_CONTENT_EXPIRED");
    records.requireDirectory(records.snapshot());
  }
}
