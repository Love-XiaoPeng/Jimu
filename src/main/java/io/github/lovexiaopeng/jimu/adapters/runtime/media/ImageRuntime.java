package io.github.lovexiaopeng.jimu.adapters.runtime.media;

import io.github.lovexiaopeng.jimu.adapters.persistence.Payloads;
import io.github.lovexiaopeng.jimu.adapters.runtime.worker.OperationThreads;
import io.github.lovexiaopeng.jimu.kernel.task.api.ArtifactStore;
import io.github.lovexiaopeng.jimu.kernel.task.api.ExecutionLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.ImageLedger;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.plugin.api.ImagePort;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Decoding is a tracked worker operation; cancellation is not a proof of decoder exit. */
public final class ImageRuntime {
  private final ArtifactStore store;
  private final OperationThreads operations;
  private final Decoder decoder;

  @FunctionalInterface
  interface Decoder {
    ImageNormalizer.Normalized normalize(
        String path, byte[] raw, io.github.lovexiaopeng.jimu.plugin.api.Cancellation cancellation);
  }

  public ImageRuntime(ArtifactStore store, OperationThreads operations) {
    this(store, operations, new ImageNormalizer()::normalize);
  }

  ImageRuntime(ArtifactStore store, OperationThreads operations, Decoder decoder) {
    this.store = store;
    this.operations = operations;
    this.decoder = decoder;
  }

  public void available() {
    if (store == null) throw new TaskFailure(503, "IMAGE_STORE_UNAVAILABLE");
    store.check();
    ImageNormalizer.available();
  }

  public ImagePort bind(
      ExecutionLedger ledger, ExecutionLedger.Lease lease, ExecutionLedger.Invocation invocation) {
    return path -> {
      try {
        io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages.path(path);
      } catch (TaskFailure invalid) {
        throw new io.github.lovexiaopeng.jimu.plugin.api.FileFailure("FS_PATH_DENIED");
      }
      available();
      if (Runtime.getRuntime().maxMemory() > 268435456L)
        throw new TaskFailure(503, "IMAGE_WORKER_HEAP_UNBOUNDED");
      var media = (ImageLedger) ledger;
      var raw = media.imageInput(lease, path);
      String id = media.reserveImage(lease, invocation.id(), raw.entry().inputId());
      var future =
          new FutureTask<>(
              () -> decoder.normalize(path, raw.bytes(), () -> ledger.checkpoint(lease)));
      Thread decoder = operations.start("jimu-image-decode", future);
      Instant until = Instant.now().plusSeconds(30);
      if (lease.deadline().isBefore(until)) until = lease.deadline();
      ImageNormalizer.Normalized result;
      try {
        while (true) {
          ledger.checkpoint(lease);
          long millis = Duration.between(Instant.now(), until).toMillis();
          if (millis <= 0) {
            media.imageDecodeTimedOut(lease);
            throw new TaskFailure(409, "IMAGE_DECODE_TIMEOUT");
          }
          try {
            result = future.get(Math.min(100, millis), TimeUnit.MILLISECONDS);
            break;
          } catch (java.util.concurrent.TimeoutException pending) {
            /* recheck authority and deadline */
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new TaskFailure(409, "CANCEL_REQUESTED");
      } catch (java.util.concurrent.ExecutionException failed) {
        if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
        if (failed.getCause() instanceof Error fatal) throw fatal;
        throw new TaskFailure(409, "INVALID_IMAGE");
      } finally {
        if (!future.isDone()) {
          future.cancel(true);
          decoder.interrupt();
          // Interrupted or revoked authority also needs the supervisor's stop deadline.
          // Future cancellation alone says nothing about a native decoder's actual exit.
          media.imageDecodeTimedOut(lease);
        }
      }
      ledger.checkpoint(lease);
      byte[] png = result.png();
      String hash = Payloads.hash(png);
      var image =
          new ImagePort.Image(
              id,
              "image/png",
              hash,
              png.length,
              result.width(),
              result.height(),
              result.originalWidth(),
              result.originalHeight(),
              ImageNormalizer.VERSION);
      var output =
          new ImagePort.Result(
              path,
              raw.entry().inputId(),
              "image:" + invocation.id(),
              raw.entry().sha256(),
              raw.entry().size(),
              image);
      String key = store.write(lease.tenant(), id, png, hash);
      store.read(lease.tenant(), key, hash, png.length);
      media.imageSucceeded(lease, invocation.id(), key, output);
      return output;
    };
  }

  public byte[] bytes(ExecutionLedger ledger, ExecutionLedger.Lease lease, ImagePort.Result ref) {
    var object = ((ImageLedger) ledger).imageObject(lease, ref);
    return store.read(lease.tenant(), object.key(), ref.image().sha256(), ref.image().size());
  }
}
