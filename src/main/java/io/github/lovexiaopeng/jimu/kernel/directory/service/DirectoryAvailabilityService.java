package io.github.lovexiaopeng.jimu.kernel.directory.service;

import io.github.lovexiaopeng.jimu.kernel.directory.port.DirectoryAvailability;

public interface DirectoryAvailabilityService {
  boolean available(DirectoryAvailability records);
}
