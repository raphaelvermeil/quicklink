package com.quicklink.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageCompressorTest {

    private final ImageCompressor compressor = new ImageCompressor();

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = (((x * 7) % 256) << 16) | (((y * 5) % 256) << 8) | ((x + y) % 256);
                image.setRGB(x, y, rgb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void scalesDownAnOversizedImage() throws Exception {
        byte[] compressed = compressor.compress(png(2048, 1024), "image/png");

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(compressed));
        assertNotNull(result);
        assertEquals(1024, result.getWidth(), "longest side must be capped at 1024");
        assertEquals(512, result.getHeight(), "aspect ratio must be preserved");
    }

    /**
     * Regression guard. Thumbnailator's size() enlarges as well as shrinks, so a naive
     * size(1024,1024) would turn a 256x256 image into 1024x1024 and make the
     * "compressed" output larger than the original.
     */
    @Test
    void neverEnlargesAnImageSmallerThanTheCap() throws Exception {
        byte[] original = png(256, 256);

        byte[] compressed = compressor.compress(original, "image/png");

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(compressed));
        assertEquals(256, result.getWidth());
        assertEquals(256, result.getHeight());
        assertTrue(compressed.length <= original.length * 2,
                "re-encoding a small image must not balloon it");
    }

    @Test
    void rejectsBytesThatAreNotAnImage() {
        assertThrows(UnsupportedMediaException.class,
                () -> compressor.compress("definitely not a png".getBytes(), "image/png"));
    }

    @Test
    void rejectsAnUnsupportedContentType() {
        assertThrows(UnsupportedMediaException.class,
                () -> compressor.compress(new byte[] {1, 2, 3}, "application/pdf"));
    }
}
