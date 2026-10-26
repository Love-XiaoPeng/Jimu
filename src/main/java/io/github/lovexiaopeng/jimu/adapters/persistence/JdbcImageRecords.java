package io.github.lovexiaopeng.jimu.adapters.persistence;

import io.github.lovexiaopeng.jimu.kernel.media.service.ImagePersistenceService;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;
import java.util.Map;

/** Compatibility only for frozen callers; no SQL, policy or transaction ownership. */
final class JdbcImageRecords {
  private final ImagePersistenceService service;
  private final MapperImageRecords records;

  JdbcImageRecords(ImagePersistenceService service, MapperImageRecords records) {
    this.service = service;
    this.records = records;
  }

  void insert(
      Tasks.Identity actor,
      String task,
      List<TaskImages.Input> inputs,
      List<TaskImages.Descriptor> descriptors) {
    service.insert(actor, task, inputs, descriptors);
  }

  TaskImages.Snapshot snapshot(Map<String, Object> task) {
    return service.snapshot(records.bind(task));
  }

  ImageLedger.Raw raw(Map<String, Object> task, String path) {
    return service.raw(records.bind(task), path);
  }

  void reserve(Map<String, Object> task, String invocation, String image, String inputId) {
    service.reserve(records.bind(task), invocation, image, inputId);
  }

  void succeeded(Map<String, Object> task, String invocation, String key, ImagePort.Result result) {
    service.succeeded(records.bind(task), invocation, key, result);
  }

  ImageLedger.ObjectValue object(Map<String, Object> task, ImagePort.Result ref) {
    return service.object(records.bind(task), ref);
  }

  List<ImageLedger.ObjectValue> step(Map<String, Object> task, String step) {
    return service.step(records.bind(task), step);
  }
}
