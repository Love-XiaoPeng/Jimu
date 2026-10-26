package io.github.lovexiaopeng.jimu.kernel.media.service;

import io.github.lovexiaopeng.jimu.kernel.media.port.ImageRecords;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;

public interface ImagePersistenceService {
  void insert(
      Tasks.Identity actor,
      String task,
      List<TaskImages.Input> inputs,
      List<TaskImages.Descriptor> descriptors);

  TaskImages.Snapshot snapshot(ImageRecords.Task task);

  ImageLedger.Raw raw(ImageRecords.Task task, String path);

  void reserve(ImageRecords.Task task, String invocation, String image, String inputId);

  void succeeded(ImageRecords.Task task, String invocation, String key, ImagePort.Result result);

  ImageLedger.ObjectValue object(ImageRecords.Task task, ImagePort.Result ref);

  List<ImageLedger.ObjectValue> step(ImageRecords.Task task, String step);

  TaskImages.Snapshot mediaSnapshot(Lease lease);

  ImageLedger.Raw imageInput(Lease lease, String path);

  String reserveImage(Lease lease, String invocation, String inputId);

  void imageSucceeded(Lease lease, String invocation, String key, ImagePort.Result result);

  ImageLedger.ObjectValue imageObject(Lease lease, ImagePort.Result ref);

  List<ImageLedger.ObjectValue> stepImages(Lease lease, String step);

  void imageDecodeTimedOut(Lease lease);
}
