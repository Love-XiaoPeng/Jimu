package io.github.lovexiaopeng.jimu.kernel.media.port;

import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger.Lease;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.kernel.task.api.Tasks;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.util.List;
import java.util.function.Supplier;

/** Lazy original rows; single persistence/codec operations, without independent authority. */
public interface ImageRecords {
  interface Task {
    String tenantId();

    String id();

    String mediaSnapshot();

    long recordVersion();

    Object mediaDigest();

    String requestSnapshot();

    String toolsSnapshot();
  }

  interface Input {
    long valid();

    Object revokedAt();

    Object rawBase64();

    Object logicalPath();

    String rawSha256();

    long rawSize();

    String inputId();

    String base64();

    String path();
  }

  interface Invocation {
    String inputSnapshot();

    String outputSnapshot();

    Object pluginVersion();

    Object pluginDigest();

    Object outputDigest();

    Object inputDigest();
  }

  interface Image {
    String imageId();

    String producerInvocation();

    String sha256();

    long size();

    long width();

    long height();

    long originalWidth();

    long originalHeight();

    String normalizerVersion();

    String snapshotKey();
  }

  interface InputId {
    String inputId();
  }

  interface Output {
    String outputSnapshot();
  }

  interface Budget {
    long storageLimit();

    long storageHeld();

    long mediaCheckpointHeld();
  }

  <T> T leaseTransaction(Lease lease, Supplier<T> work);

  Task fenced(Lease lease, boolean admission);

  Budget budget(String tenant);

  void requireImages(Task task);

  void event(Task task, String fact, String type, String code);

  void reserveStorage(
      String tenant,
      String task,
      String image,
      String dispatch,
      String category,
      long amount,
      String state,
      String outcome);

  void toolSucceeded(Lease lease, String invocation, String output);

  String id();

  String json(Object value);

  String hash(String value);

  String hash(byte[] value);

  TaskImages.Snapshot readSnapshot(String raw);

  Tasks.Request readRequest(String raw);

  ExecutionLedger.ToolSnapshot readTools(String raw);

  ImagePort.Result readResult(String raw);

  String filePath(String raw);

  byte[] decode(String raw);

  int insertInput(
      String tenant,
      String task,
      String inputId,
      String logicalPath,
      String sha256,
      long size,
      String rawBase64);

  int saveMediaSnapshot(String snapshot, String digest, String tenant, String task);

  List<InputId> selectInputIds(String tenant, String task);

  List<Input> selectImageInput(String tenant, String task, String inputId);

  List<Invocation> selectRunningImageInvocation(String tenant, String task, String invocation);

  List<Image> selectProducerImages(String tenant, String invocation);

  int reserveImageObject(
      String tenant,
      String task,
      String image,
      String invocation,
      String inputId,
      String normalizer);

  int commitImageObject(
      String key,
      String sha256,
      long size,
      int width,
      int height,
      int originalWidth,
      int originalHeight,
      String tenant,
      String task,
      String image,
      String invocation,
      String inputId);

  List<Image> selectRetainedImageObject(String tenant, String task, String image);

  List<Invocation> selectSucceededImageInvocation(String tenant, String task, String invocation);

  List<Output> selectStepImageOutputs(String tenant, String task, String step);

  int holdImageStorage(String tenant);

  int settleImageReservation(long actual, String tenant, String image);

  int releaseImageStorage(long bytes, String tenant);

  int cancelImageDecode(String tenant, String task);
}
