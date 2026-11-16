package com.newcodes7.small_town.crawler.integration.storage;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.JpegWriter;
import com.sksamuel.scrimage.nio.PngWriter;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * 아티클/영상 썸네일을 화면·og:image 용도에 맞는 크기와 형식으로 줄인다.
 *
 * <p>이전에는 원본 해상도 그대로 PNG(MaxCompression)로 저장해 한 장에 1~2.6MB가 나왔고,
 * 홈 한 페이지가 8.7MB였다(Lighthouse 이미지 전달 절감 추정 5.5~6.7MB).
 *
 * <ul>
 *   <li>가로 800px 초과면 800px로 축소한다(카드 최대 약 400 CSS px × DPR 2, og:image 권장 최소 600px 충족). 확대는 하지 않는다.
 *   <li>실제로 투명 픽셀이 있으면 PNG, 아니면 JPEG(q82, progressive). WebP는 일부 링크 미리보기 크롤러가
 *       og:image로 처리하지 못해(썸네일을 PNG로 바꾼 e3825936 이후 유지) 쓰지 않는다.
 * </ul>
 */
public final class ThumbnailOptimizer {

    public static final int MAX_WIDTH = 800;
    private static final int JPEG_QUALITY = 82;

    public record Result(byte[] data, String extension, String contentType, int width, int height) {
    }

    private ThumbnailOptimizer() {
    }

    public static Result optimize(byte[] imageData) throws IOException {
        ImmutableImage image = ImmutableImage.loader().fromBytes(imageData);
        if (image.width > MAX_WIDTH) {
            image = image.scaleToWidth(MAX_WIDTH);
        }

        if (usesTransparency(image.awt())) {
            return new Result(image.bytes(PngWriter.MaxCompression), "png", "image/png", image.width, image.height);
        }

        // JPEG은 알파 채널을 못 담으므로 RGB로 옮겨 쓴다(ARGB 그대로면 ImageIO가 색이 깨지거나 실패한다)
        BufferedImage rgb = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB);
        var g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.width, image.height);
            g.drawImage(image.awt(), 0, 0, null);
        } finally {
            g.dispose();
        }
        byte[] jpeg = ImmutableImage.wrapAwt(rgb).bytes(new JpegWriter(JPEG_QUALITY, true));
        return new Result(jpeg, "jpg", "image/jpeg", image.width, image.height);
    }

    /** 알파 채널이 있어도 전부 불투명이면(대부분의 PNG 스크린샷) false — 그런 이미지는 JPEG이 훨씬 작다. */
    static boolean usesTransparency(BufferedImage image) {
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int argb : row) {
                if ((argb >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }
}
