package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Input;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Proof;
import io.github.lovexiaopeng.jimu.kernel.session.port.SessionScopeNormalization.Stored;

public interface SessionScopeNormalizationService {
  String normalize(Input input);

  void verify(String expected, Stored stored, Proof proof);
}
