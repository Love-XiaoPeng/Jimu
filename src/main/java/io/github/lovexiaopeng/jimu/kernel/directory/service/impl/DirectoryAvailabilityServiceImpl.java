package io.github.lovexiaopeng.jimu.kernel.directory.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAvailability;
import io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryAvailabilityService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;

/** Keep the flag outside try, original first row and two separately evaluated descriptors. */
public final class DirectoryAvailabilityServiceImpl implements DirectoryAvailabilityService {
  public boolean available(DirectoryAvailability records) {
    if (!records.enabled()) return false;
    try {
      records.requireDirectory(records.selected());
      var installed = records.installed();
      if (installed.isEmpty()) throw new TaskFailure(404, "NOT_FOUND");
      var row = installed.get(0);
      return records.descriptor().version().equals(row.version())
          && records.descriptor().outputSha256().equals(row.digest());
    } catch (TaskFailure rejected) {
      return false;
    }
  }
}
