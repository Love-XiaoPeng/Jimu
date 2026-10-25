package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionMetadataAccounting;

/** Metadata capacity and convergence; does not open a transaction or prefetch rows. */
public interface SessionMetadataAccountingService {
  long metadata(SessionMetadataAccounting records, boolean closing, long planned);

  void account(SessionMetadataAccounting records, boolean closing);
}
