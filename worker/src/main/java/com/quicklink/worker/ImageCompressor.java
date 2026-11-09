package com.quicklink.worker;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

@Component
class ImageCompressor {

    private static final int MAX_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.85f;

    byte[] compress(byte[] original, String contentType) throws IOException {
        String format = formatFor(contentType);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null) {
            throw new UnsupportedMediaException("not a decodable image: " + contentType);
        }

        // Scale DOWN only. Thumbnailator's size() would happily enlarge a small image,
        // which would make the "compressed" output bigger than the original.
        double scale = Math.min(1.0,
                (double) MAX_DIMENSION / Math.max(image.getWidth(), image.getHeight()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(image)
                .scale(scale)
                .outputFormat(format);
        if (!"png".equals(format)) {
            builder.outputQuality(JPEG_QUALITY);   // ignored by lossless formats
        }
        builder.toOutputStream(out);
        return out.toByteArray();
    }

    private static String formatFor(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        return switch (type) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> throw new UnsupportedMediaException("unsupported content type: " + contentType);
        };
    }
}
