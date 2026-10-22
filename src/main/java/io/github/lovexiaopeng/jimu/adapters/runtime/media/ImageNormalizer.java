package io.github.lovexiaopeng.jimu.adapters.runtime.media;

import io.github.lovexiaopeng.jimu.plugin.api.Cancellation;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.StreamSupport;
import java.util.zip.CRC32;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Bounded worker-only codec. No disk cache, dynamic SPI scan, native library or source path. */
public final class ImageNormalizer {
  private static <T> java.util.stream.Stream<T> providers(Class<T> type) {
    // IIORegistry registers JDK codecs internally (they are not JPMS service providers).
    // Never call scanForPlugins; no classpath/third-party codec may execute this read.
    Iterable<T> installed = () -> IIORegistry.getDefaultInstance().getServiceProviders(type, false);
    return StreamSupport.stream(installed.spliterator(), false);
  }

  public static final String VERSION = "jimu-image-v1";
  public static final int MAX_BYTES = 1048576;
  public static final long MAX_PIXELS = 4194304L;

  public record Normalized(
      byte[] png, int width, int height, int originalWidth, int originalHeight) {
    public Normalized {
      png = png.clone();
    }

    @Override
    public byte[] png() {
      return png.clone();
    }
  }

  public Normalized normalize(String path, byte[] raw, Cancellation cancellation) {
    cancellation.throwIfCancelled();
    if (raw.length < 1 || raw.length > MAX_BYTES) throw new FileFailure("IMAGE_TOO_LARGE");
    boolean png =
        raw.length >= 8
            && Arrays.equals(
                Arrays.copyOf(raw, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    boolean jpeg = raw.length >= 4 && (raw[0] & 255) == 255 && (raw[1] & 255) == 216;
    if (!png && !jpeg) throw new FileFailure("IMAGE_FORMAT_UNSUPPORTED");
    String extension = path.substring(path.lastIndexOf('/') + 1);
    int dot = extension.lastIndexOf('.');
    if (dot >= 0) {
      extension = extension.substring(dot + 1).toLowerCase(Locale.ROOT);
      if (!java.util.List.of("png", "jpg", "jpeg").contains(extension))
        throw new FileFailure("IMAGE_FORMAT_UNSUPPORTED");
      if (png != extension.equals("png")) throw new FileFailure("IMAGE_TYPE_MISMATCH");
    }
    int orientation = png ? pngContainer(raw) : jpegContainer(raw);
    String readerName =
        "com.sun.imageio.plugins." + (png ? "png.PNGImageReaderSpi" : "jpeg.JPEGImageReaderSpi");
    var spi =
        providers(ImageReaderSpi.class)
            .filter(
                p ->
                    p.getClass().getModule().getName().equals("java.desktop")
                        && p.getClass().getName().equals(readerName))
            .findFirst()
            .orElseThrow(() -> new FileFailure("IMAGE_CODEC_UNAVAILABLE"));
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))) {
      var reader = spi.createReaderInstance();
      try {
        reader.addIIOReadWarningListener(
            (source, warning) -> {
              throw invalid();
            });
        reader.setInput(input, false, false);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        dimensions(width, height);
        if (reader.getNumImages(true) != 1) throw new FileFailure("IMAGE_FORMAT_UNSUPPORTED");
        cancellation.throwIfCancelled();
        var decoded = reader.read(0);
        if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height)
          throw invalid();
        cancellation.throwIfCancelled();
        var oriented = orient(decoded, orientation);
        int ow = oriented.getWidth(), oh = oriented.getHeight();
        double scale =
            Math.min(
                1.0, Math.min(2048.0 / Math.max(ow, oh), Math.sqrt(1048576.0 / ((long) ow * oh))));
        int w = Math.max(1, (int) Math.floor(ow * scale)),
            h = Math.max(1, (int) Math.floor(oh * scale));
        while (true) {
          cancellation.throwIfCancelled();
          var image = resize(oriented, w, h);
          byte[] bytes = encode(image);
          if (bytes.length <= MAX_BYTES) return new Normalized(bytes, w, h, ow, oh);
          if (w == 1 && h == 1) throw new FileFailure("IMAGE_TOO_LARGE");
          w = Math.max(1, w / 2);
          h = Math.max(1, h / 2);
        }
      } finally {
        reader.dispose();
      }
    } catch (IOException | IllegalArgumentException e) {
      throw invalid();
    }
  }

  public static void dimensions(int width, int height) {
    if (width < 1 || height < 1) throw invalid();
    if (width > 8192 || height > 8192) throw new FileFailure("IMAGE_DIMENSION_TOO_LARGE");
    if ((long) width * height > MAX_PIXELS) throw new FileFailure("IMAGE_TOO_MANY_PIXELS");
  }

  private static byte[] encode(BufferedImage image) throws IOException {
    var spi =
        providers(ImageWriterSpi.class)
            .filter(
                p ->
                    p.getClass().getModule().getName().equals("java.desktop")
                        && p.getClass()
                            .getName()
                            .equals("com.sun.imageio.plugins.png.PNGImageWriterSpi"))
            .findFirst()
            .orElseThrow(() -> new FileFailure("IMAGE_CODEC_UNAVAILABLE"));
    var writer = spi.createWriterInstance();
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      writer.write(image);
      output.flush();
      return bytes.toByteArray();
    } finally {
      writer.dispose();
    }
  }

  private static BufferedImage resize(BufferedImage source, int w, int h) {
    var image =
        new BufferedImage(
            w,
            h,
            source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB);
    var graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(source, 0, 0, w, h, null);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static BufferedImage orient(BufferedImage source, int orientation) {
    int w = source.getWidth(), h = source.getHeight();
    boolean swap = orientation >= 5;
    var target =
        new BufferedImage(
            swap ? h : w,
            swap ? w : h,
            source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB
                : BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int tx =
            switch (orientation) {
              case 2, 3 -> w - 1 - x;
              case 5, 8 -> y;
              case 6, 7 -> h - 1 - y;
              default -> x;
            };
        int ty =
            switch (orientation) {
              case 3, 4 -> h - 1 - y;
              case 5, 6 -> x;
              case 7, 8 -> w - 1 - x;
              default -> y;
            };
        target.setRGB(tx, ty, source.getRGB(x, y));
      }
    return target;
  }

  private static int pngContainer(byte[] raw) {
    int at = 8;
    boolean header = false, data = false;
    while (at <= raw.length - 12) {
      long length = Integer.toUnsignedLong(ByteBuffer.wrap(raw, at, 4).getInt());
      if (length > raw.length - at - 12) throw invalid();
      int size = (int) length;
      String type = new String(raw, at + 4, 4, StandardCharsets.US_ASCII);
      var crc = new CRC32();
      crc.update(raw, at + 4, size + 4);
      if (crc.getValue() != Integer.toUnsignedLong(ByteBuffer.wrap(raw, at + 8 + size, 4).getInt()))
        throw invalid();
      if (!header && (!type.equals("IHDR") || size != 13)) throw invalid();
      if (type.equals("IHDR")) {
        if (header) throw invalid();
        header = true;
        dimensions(
            ByteBuffer.wrap(raw, at + 8, 4).getInt(), ByteBuffer.wrap(raw, at + 12, 4).getInt());
      }
      if (java.util.List.of("acTL", "fcTL", "fdAT").contains(type))
        throw new FileFailure("IMAGE_FORMAT_UNSUPPORTED");
      if (type.equals("IDAT")) data = true;
      at += size + 12;
      if (type.equals("IEND")) {
        if (size != 0 || !data || at != raw.length) throw invalid();
        return 1;
      }
    }
    throw invalid();
  }

  private static int jpegContainer(byte[] raw) {
    if ((raw[raw.length - 2] & 255) != 255 || (raw[raw.length - 1] & 255) != 217) throw invalid();
    int at = 2, orientation = 1;
    boolean exif = false, frame = false, scan = false;
    while (at < raw.length) {
      if ((raw[at++] & 255) != 255) throw invalid();
      while (at < raw.length && (raw[at] & 255) == 255) at++;
      if (at == raw.length) throw invalid();
      int marker = raw[at++] & 255;
      if (marker == 217) {
        if (at != raw.length || !frame || !scan) throw invalid();
        return orientation;
      }
      if (marker == 0 || marker == 216 || marker >= 208 && marker <= 215 || at > raw.length - 2)
        throw invalid();
      int size = ((raw[at] & 255) << 8) | (raw[at + 1] & 255);
      if (size < 2 || size > raw.length - at) throw invalid();
      if (marker >= 192 && marker <= 207 && !java.util.List.of(196, 200, 204).contains(marker)) {
        if (!java.util.List.of(192, 193, 194).contains(marker)
            || frame
            || size < 8
            || raw[at + 2] != 8
            || !java.util.List.of(1, 3).contains(raw[at + 7] & 255))
          throw new FileFailure("IMAGE_FORMAT_UNSUPPORTED");
        dimensions(
            ((raw[at + 5] & 255) << 8) | (raw[at + 6] & 255),
            ((raw[at + 3] & 255) << 8) | (raw[at + 4] & 255));
        frame = true;
      }
      if (marker == 225
          && size >= 8
          && Arrays.equals(
              Arrays.copyOfRange(raw, at + 2, at + 8), new byte[] {69, 120, 105, 102, 0, 0})) {
        if (exif) throw invalid();
        exif = true;
        orientation = exif(raw, at + 8, size - 8);
      }
      at += size;
      if (marker == 218) {
        scan = true;
        while (at < raw.length) {
          if ((raw[at] & 255) != 255) {
            at++;
            continue;
          }
          int start = at++;
          while (at < raw.length && (raw[at] & 255) == 255) at++;
          if (at == raw.length) throw invalid();
          int next = raw[at] & 255;
          if (next == 0 || next >= 208 && next <= 215) {
            at++;
            continue;
          }
          at = start;
          break;
        }
      }
    }
    throw invalid();
  }

  private static int exif(byte[] raw, int at, int length) {
    if (length < 8) throw invalid();
    var tiff = ByteBuffer.wrap(raw, at, length).slice();
    short endian = tiff.getShort(0);
    if (endian != 0x4949 && endian != 0x4d4d) throw invalid();
    tiff.order(endian == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    if (tiff.getShort(2) != 42) throw invalid();
    long offset = Integer.toUnsignedLong(tiff.getInt(4));
    if (offset < 8 || offset > length - 2) throw invalid();
    int count = Short.toUnsignedInt(tiff.getShort((int) offset));
    if (offset + 2L + count * 12L + 4 > length) throw invalid();
    int result = 1;
    boolean found = false;
    for (int i = 0; i < count; i++) {
      int entry = (int) offset + 2 + 12 * i;
      int type = Short.toUnsignedInt(tiff.getShort(entry + 2));
      int unit =
          switch (type) {
            case 1, 2, 6, 7 -> 1;
            case 3, 8 -> 2;
            case 4, 9, 11 -> 4;
            case 5, 10, 12 -> 8;
            default -> throw invalid();
          };
      long bytes = Integer.toUnsignedLong(tiff.getInt(entry + 4)) * unit;
      if (bytes > 4) {
        long value = Integer.toUnsignedLong(tiff.getInt(entry + 8));
        if (value > length || bytes > length - value) throw invalid();
      }
      if (Short.toUnsignedInt(tiff.getShort(entry)) == 274) {
        if (found || type != 3 || tiff.getInt(entry + 4) != 1) throw invalid();
        result = Short.toUnsignedInt(tiff.getShort(entry + 8));
        found = true;
        if (result < 1 || result > 8) throw invalid();
      }
    }
    return result;
  }

  private static FileFailure invalid() {
    return new FileFailure("INVALID_IMAGE");
  }
}
