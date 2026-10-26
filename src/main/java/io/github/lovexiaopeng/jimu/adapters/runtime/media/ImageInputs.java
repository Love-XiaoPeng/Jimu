package io.github.lovexiaopeng.jimu.adapters.runtime.media;

import io.github.lovexiaopeng.jimu.adapters.codec.Payloads;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskFailure;
import io.github.lovexiaopeng.jimu.kernel.task.api.TaskImages;
import io.github.lovexiaopeng.jimu.plugin.api.ImageErrorCode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Validates raw input at the Java admission boundary, without decoding any pixels. */
public final class ImageInputs {
  private ImageInputs() {}

  public static List<TaskImages.Descriptor> describe(List<TaskImages.Input> inputs) {
    if (inputs == null) return null;
    if (inputs.isEmpty() || inputs.size() > 4) throw invalid();
    var result = new ArrayList<TaskImages.Descriptor>();
    long total = 0;
    for (var input : inputs) {
      if (input == null) throw invalid();
      String path = TaskImages.path(input.filePath());
      for (var old : result)
        if (old.filePath().equals(path)
            || old.filePath().startsWith(path + "/")
            || path.startsWith(old.filePath() + "/")) throw invalid();
      byte[] bytes = decode(input.dataBase64());
      total += bytes.length;
      if (total > 4194304) throw invalid();
      result.add(new TaskImages.Descriptor(path, Payloads.hash(bytes), bytes.length));
    }
    return List.copyOf(result);
  }

  public static byte[] decode(String value) {
    if (value == null || value.isEmpty() || value.length() > 1398104 || value.length() % 4 != 0)
      throw invalid();
    try {
      byte[] bytes = Base64.getDecoder().decode(value);
      if (bytes.length < 1
          || bytes.length > 1048576
          || !Base64.getEncoder().encodeToString(bytes).equals(value)) throw invalid();
      return bytes;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static TaskFailure invalid() {
    return new TaskFailure(400, ImageErrorCode.INVALID_IMAGE_INPUT);
  }
}
