`red-3x2.jpg` is a synthetic 3x2 RGB image generated locally with the JDK21 JPEG writer, quality defaults. All six pixels are RGB (240,32,16); it contains no external image or business data. The controlled SSE vertical test submits this JPEG and verifies the actual normalized PNG received over HTTP.

Regeneration: create a `BufferedImage(3,2,TYPE_INT_RGB)`, set every pixel to `0xfff02010`, then call `ImageIO.write(image,"jpeg",file)`.
