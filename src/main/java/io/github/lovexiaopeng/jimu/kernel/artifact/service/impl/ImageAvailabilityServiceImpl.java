package io.github.lovexiaopeng.jimu.kernel.artifact.service.impl;

import io.github.lovexiaopeng.jimu.kernel.artifact.port.ImageAvailability;
import io.github.lovexiaopeng.jimu.kernel.artifact.service.ImageAvailabilityService;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;

/**
 * Original store existence outside try, followed by ordered checks and only FileFailure handling.
 */
public final class ImageAvailabilityServiceImpl implements ImageAvailabilityService {
  public boolean available(ImageAvailability checks) {
    if (!checks.storePresent()) return false;
    try {
      checks.checkStore();
      checks.checkCodec();
      return true;
    } catch (FileFailure unavailable) {
      return false;
    }
  }
}
