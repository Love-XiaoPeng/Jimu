package io.github.lovexiaopeng.jimu.adapters.runtime.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lovexiaopeng.jimu.adapters.runtime.contract.WorkspaceContracts;
import io.github.lovexiaopeng.jimu.plugin.api.FileFailure;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageNormalizerTest {
  private final ImageNormalizer codec = new ImageNormalizer();

  private byte[] image(String format, int w, int h) throws Exception {
    var source =
        new BufferedImage(
            w, h, format.equals("jpg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
    source.setRGB(0, 0, 0xffff0000);
    var output = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(source, format, output));
    return output.toByteArray();
  }

  private void failure(String code, String path, byte[] raw) {
    assertEquals(
        code, assertThrows(FileFailure.class, () -> codec.normalize(path, raw, () -> {})).code());
  }

  @Test
  void bothFormatsBecomeActualPngAndNeverUpscale() throws Exception {
    for (String format : java.util.List.of("png", "jpg")) {
      var normalized =
          codec.normalize("inputs/images/a." + format.toUpperCase(), image(format, 7, 3), () -> {});
      assertEquals(7, normalized.width());
      assertEquals(3, normalized.height());
      assertEquals(7, normalized.originalWidth());
      var decoded = ImageIO.read(new ByteArrayInputStream(normalized.png()));
      assertEquals(7, decoded.getWidth());
      assertArrayEquals(
          new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, Arrays.copyOf(normalized.png(), 8));
    }
  }

  @Test
  void detectsSignatureWithoutExtensionAndRejectsDeclaredMismatch() throws Exception {
    var png = image("png", 1, 1);
    assertEquals(1, codec.normalize("inputs/images/a", png, () -> {}).width());
    failure("IMAGE_TYPE_MISMATCH", "a.jpg", png);
    for (String ext : java.util.List.of("gif", "webp", "svg", "bmp", "pdf"))
      failure("IMAGE_FORMAT_UNSUPPORTED", "a." + ext, png);
    failure("IMAGE_FORMAT_UNSUPPORTED", "a.png", new byte[] {1, 2, 3});
  }

  @Test
  void completeDecodeAndContainerChecksRejectCorruption() throws Exception {
    var png = image("png", 8, 8);
    failure("INVALID_IMAGE", "a.png", Arrays.copyOf(png, png.length - 1));
    png[29] ^= 1;
    failure("INVALID_IMAGE", "a.png", png);
    var jpeg = image("jpg", 8, 8);
    failure("INVALID_IMAGE", "a.jpg", Arrays.copyOf(jpeg, jpeg.length - 2));
    jpeg[jpeg.length - 4] = 0;
    // Valid EOI alone cannot rescue a truncated entropy stream.
    var truncated = Arrays.copyOf(jpeg, 30);
    truncated[28] = (byte) 255;
    truncated[29] = (byte) 217;
    failure("INVALID_IMAGE", "a.jpg", truncated);
  }

  @Test
  void rejectsPixelAndByteLimitsBeforeDecode() throws Exception {
    failure("IMAGE_TOO_LARGE", "a.png", new byte[1048577]);
    assertThrows(
        FileFailure.class, () -> ImageNormalizer.dimensions(Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertEquals(
        "IMAGE_TOO_MANY_PIXELS",
        assertThrows(FileFailure.class, () -> ImageNormalizer.dimensions(4096, 1025)).code());
    ImageNormalizer.dimensions(4096, 1024);
    var raw = image("png", 1, 1);
    ByteBuffer.wrap(raw, 16, 4).putInt(8193);
    var crc = new CRC32();
    crc.update(raw, 12, 17);
    ByteBuffer.wrap(raw, 29, 4).putInt((int) crc.getValue());
    failure("IMAGE_DIMENSION_TOO_LARGE", "a.png", raw);
  }

  @Test
  void scalesLargeImagesWithinBothOutputLimits() throws Exception {
    var result = codec.normalize("a.png", image("png", 4096, 1024), () -> {});
    assertEquals(4096, result.originalWidth());
    assertEquals(1024, result.originalHeight());
    assertEquals(2048, result.width());
    assertEquals(512, result.height());
    assertTrue(result.png().length <= ImageNormalizer.MAX_BYTES);
  }

  private byte[] chunk(byte[] png, String type, byte[] data) throws Exception {
    var result = new ByteArrayOutputStream();
    result.write(png, 0, 33);
    var output = new java.io.DataOutputStream(result);
    output.writeInt(data.length);
    byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    output.write(name);
    output.write(data);
    var crc = new CRC32();
    crc.update(name);
    crc.update(data);
    output.writeInt((int) crc.getValue());
    result.write(png, 33, png.length - 33);
    return result.toByteArray();
  }

  private byte[] icc(byte[] bytes) throws Exception {
    var result = new ByteArrayOutputStream();
    result.write(new byte[] {'p', 0, 0});
    try (var zip = new java.util.zip.DeflaterOutputStream(result)) {
      zip.write(bytes);
    }
    return result.toByteArray();
  }

  @Test
  void boundsIccInflationAndStripsMetadataWithoutLosingAlphaOrSixteenBitPixels() throws Exception {
    byte[] png = image("png", 3, 2);
    failure("INVALID_IMAGE", "a.png", chunk(png, "iCCP", icc(new byte[65537])));
    failure("INVALID_IMAGE", "a.png", chunk(png, "iCCP", icc(new byte[128])));
    var profile =
        java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData();
    var result = codec.normalize("a.png", chunk(png, "iCCP", icc(profile)), () -> {});
    var decoded = ImageIO.read(new ByteArrayInputStream(result.png()));
    assertEquals(0xffff0000, decoded.getRGB(0, 0));
    assertEquals(0, decoded.getRGB(1, 0) >>> 24);
    assertFalse(
        new String(result.png(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("iCCP"));
    var gray = new BufferedImage(2, 1, BufferedImage.TYPE_USHORT_GRAY);
    gray.getRaster().setSample(0, 0, 0, 65535);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(gray, "png", bytes);
    var normalized = codec.normalize("a.png", bytes.toByteArray(), () -> {});
    assertEquals(0xffffffff, ImageIO.read(new ByteArrayInputStream(normalized.png())).getRGB(0, 0));
    failure("IMAGE_FORMAT_UNSUPPORTED", "a.png", chunk(png, "acTL", new byte[8]));
  }

  @Test
  void exifOrientationsApplyBeforeDimensionsAndMetadataIsStripped() throws Exception {
    var jpeg = image("jpg", 7, 3);
    for (int orientation = 1; orientation <= 8; orientation++) {
      var output = new ByteArrayOutputStream();
      output.write(jpeg, 0, 2);
      output.write(
          new byte[] {
            (byte) 255,
            (byte) 225,
            0,
            34,
            69,
            120,
            105,
            102,
            0,
            0,
            73,
            73,
            42,
            0,
            8,
            0,
            0,
            0,
            1,
            0,
            18,
            1,
            3,
            0,
            1,
            0,
            0,
            0,
            (byte) orientation,
            0,
            0,
            0,
            0,
            0,
            0,
            0
          });
      output.write(jpeg, 2, jpeg.length - 2);
      var result = codec.normalize("a.jpg", output.toByteArray(), () -> {});
      assertEquals(orientation >= 5 ? 3 : 7, result.originalWidth());
      assertEquals(orientation >= 5 ? 7 : 3, result.originalHeight());
      assertFalse(
          new String(result.png(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Exif"));
    }
  }

  @Test
  void cancellationPropagatesAndBothContractVersionsRemainAvailable() throws Exception {
    var raw = image("png", 1, 1);
    var cancel = new IllegalStateException("cancel");
    assertSame(
        cancel,
        assertThrows(
            IllegalStateException.class,
            () ->
                codec.normalize(
                    "a.png",
                    raw,
                    () -> {
                      throw cancel;
                    })));
    var contracts = new WorkspaceContracts();
    assertEquals("0.1.0", contracts.load("compose_report").descriptor().version());
    assertEquals("0.2.0", contracts.load("compose_report", "0.2.0").descriptor().version());
    assertEquals("read_image", contracts.load("read_image").descriptor().capability());
    assertThrows(IllegalArgumentException.class, () -> contracts.load("read_image", "0.2.0"));
  }
}
