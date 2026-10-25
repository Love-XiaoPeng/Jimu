package io.github.lovexiaopeng.jimu.kernel.session.service;

import io.github.lovexiaopeng.jimu.kernel.session.port.SessionEventAppend;

public interface SessionEventAppendService {
  long append(SessionEventAppend append);
}
