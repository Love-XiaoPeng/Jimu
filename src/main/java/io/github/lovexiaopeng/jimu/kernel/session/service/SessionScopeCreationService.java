package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeCreationRecords.Row;
import java.util.List;

public interface SessionScopeCreationService {
  void create(Row caller, List<String> ids);
}
