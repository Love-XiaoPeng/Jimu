package io.github.lovexiaopeng.jimu.kernel.event.service;

import io.github.lovexiaopeng.jimu.kernel.event.port.EventRecords;

/** 可靠事件的幂等、保留名额和跨模块计费编排。 */
public interface EventRecordingService {
  void record(EventRecords records, String fact, String type, String code);
}
