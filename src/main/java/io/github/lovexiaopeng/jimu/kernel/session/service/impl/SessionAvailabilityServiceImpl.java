package io.github.lovexiaopeng.jimu.kernel.session.service.impl;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionAvailability;
import io.github.lovexiaopeng.jimu.kernel.session.service.SessionAvailabilityService;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import java.util.List;

/** 保留 try 外短路、List.of 预求值、首行与严格行数之别及原重复选择。 */
public final class SessionAvailabilityServiceImpl implements SessionAvailabilityService {
  public boolean lineage(SessionAvailability records) {
    if (!records.lineageEnabled() || !records.readEnabled() || !records.priorEnabled())
      return false;
    try {
      records.requireRead(records.selected());
      var descriptors = records.lineageDescriptors();
      if (descriptors.size() != 3) return false;
      for (var descriptor : descriptors) {
        var installed = records.installed(descriptor.capability());
        if (installed.size() != 1
            || !descriptor.version().equals(installed.getFirst().version())
            || !descriptor.outputSha256().equals(installed.getFirst().digest())) return false;
      }
      return true;
    } catch (TaskFailure rejected) {
      return false;
    }
  }

  public boolean trace(SessionAvailability records) {
    if (!records.readEnabled() || !records.searchEnabled() || !records.traceEnabled()) return false;
    try {
      records.requireTrace(records.selected());
      for (var descriptor : List.of(records.traceRead(), records.traceSearch(), records.trace())) {
        var installed = records.installed(descriptor.capability());
        if (installed.size() != 1
            || !descriptor.version().equals(installed.getFirst().version())
            || !descriptor.outputSha256().equals(installed.getFirst().digest())) return false;
      }
      return true;
    } catch (TaskFailure rejected) {
      return false;
    }
  }

  public boolean prior(SessionAvailability records) {
    if (!records.readEnabled() || !records.priorEnabled()) return false;
    try {
      records.requireSearch(records.selected());
      for (var descriptor : List.of(records.priorRead(), records.prior())) {
        var installed = records.installed(descriptor.capability());
        if (installed.size() != 1
            || !descriptor.version().equals(installed.getFirst().version())
            || !descriptor.outputSha256().equals(installed.getFirst().digest())) return false;
      }
      return true;
    } catch (TaskFailure rejected) {
      return false;
    }
  }

  public boolean session(SessionAvailability records, boolean search) {
    if (!search && lineage(records)) return true;
    if (!search && prior(records)) return true;
    if (!records.readEnabled() || search && !records.searchEnabled()) return false;
    try {
      if (search) records.requireSearch(records.selected());
      else records.requireRead(records.selected());
      var read = records.readInstallation();
      var descriptor = records.readDescriptor(search);
      if (!descriptor.version().equals(read.version())
          || !descriptor.outputSha256().equals(read.digest()))
        return search ? trace(records) : session(records, true);
      if (!search) return true;
      var installed = records.searchInstallation();
      return records.searchDescriptor().version().equals(installed.version())
          && records.searchDescriptor().outputSha256().equals(installed.digest());
    } catch (TaskFailure rejected) {
      return false;
    }
  }
}
