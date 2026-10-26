package io.github.lovexiaopeng.jimu.kernel.directory.service.impl;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryEligibility;
import io.github.lovexiaopeng.jimu.kernel.directory.service.DirectoryEligibilityService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskErrorCode;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ToolCapability;
import java.util.HashSet;
import java.util.Set;

/** Keep the original set check, unsorted repeated capabilities and lazy pin comparisons. */
public final class DirectoryEligibilityServiceImpl implements DirectoryEligibilityService {
  public void access(DirectoryEligibility records) {
    if (!new HashSet<>(records.capabilities())
        .equals(
            Set.of(
                ToolCapability.WORKING_DIRECTORY,
                ToolCapability.READ,
                ToolCapability.WRITE,
                ToolCapability.EDIT))) throw new TaskFailure(409, TaskErrorCode.SNAPSHOT_CORRUPT);
    for (String name : records.capabilities()) {
      var pin = records.pin(name);
      var installed = records.installation(name);
      var descriptor =
          name.equals(ToolCapability.WORKING_DIRECTORY)
              ? records.directoryDescriptor()
              : records.fileDescriptor(name);
      if (!pin.version().equals(descriptor.version())
          || !pin.digest().equals(descriptor.outputSha256())
          || !pin.version().equals(installed.version())
          || !pin.digest().equals(installed.digest())
          || pin.installationEpoch() != installed.epoch())
        throw new TaskFailure(409, TaskErrorCode.PLUGIN_PIN_MISMATCH);
    }
    records.projection();
  }
}
