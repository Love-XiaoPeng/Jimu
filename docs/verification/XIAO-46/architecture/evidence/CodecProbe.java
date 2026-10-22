import javax.imageio.ImageIO;
import java.util.Arrays;

public class CodecProbe {
  public static void main(String[] args) {
    System.out.println("java=" + System.getProperty("java.runtime.version"));
    System.out.println("vendor=" + System.getProperty("java.vendor"));
    System.out.println("readers=" + Arrays.toString(ImageIO.getReaderFormatNames()));
    System.out.println("writers=" + Arrays.toString(ImageIO.getWriterFormatNames()));
    for (String format : new String[] {"PNG", "JPEG", "GIF", "WebP"}) {
      var readers = ImageIO.getImageReadersByFormatName(format);
      while (readers.hasNext()) {
        var reader = readers.next();
        System.out.println(format + "=" + reader.getClass().getName());
        reader.dispose();
      }
    }
  }
}
