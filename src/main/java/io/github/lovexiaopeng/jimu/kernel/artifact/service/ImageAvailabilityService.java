package io.github.lovexiaopeng.jimu.kernel.artifact.service;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ImageAvailability;

public interface ImageAvailabilityService {
  boolean available(ImageAvailability checks);
}
