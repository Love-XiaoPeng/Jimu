package io.github.lovexiaopeng.jimu.kernel.directory.port;

import io.github.lovexiaopeng.jimu.model.api.ModelSnapshot;

/** Values are read only at the original admission point in the caller's unit of work. */
public interface DirectoryAdmission {
  boolean enabled();

  Projection projection();

  ModelSnapshot snapshot();

  void requireDirectory(ModelSnapshot snapshot);

  interface Projection {
    String contentState();
  }
}
