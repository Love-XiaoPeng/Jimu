package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionFacts;

public interface SessionFactService {
  long append(String fact, SessionFacts.Append records);

  void record(String fact, SessionFacts.Fact records);
}
