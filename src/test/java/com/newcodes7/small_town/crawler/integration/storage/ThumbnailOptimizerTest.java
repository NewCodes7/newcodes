package com.newcodes7.small_town.crawler.integration.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.nio.PngWriter;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThumbnailOptimizerTest {

    @Test
    @DisplayName("불투명한 큰 PNG(스크린샷)는 가로 800px JPEG로 줄고 용량이 크게 준다")
    void 불투명_큰이미지_JPEG() throws Exception {
        byte[] png = png(2400, 1260, false);

        ThumbnailOptimizer.Result result = ThumbnailOptimizer.optimize(png);

        assertThat(result.extension()).isEqualTo("jpg");
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.width()).isEqualTo(800);
        assertThat(result.height()).isEqualTo(420);
        assertThat(result.data().length).isLessThan(png.length / 4);
        assertThat(ImmutableImage.loader().fromBytes(result.data()).width).isEqualTo(800);
    }

    @Test
    @DisplayName("실제로 투명한 픽셀이 있으면 PNG를 유지한다")
    void 투명_PNG유지() throws Exception {
        ThumbnailOptimizer.Result result = ThumbnailOptimizer.optimize(png(1600, 900, true));

        assertThat(result.extension()).isEqualTo("png");
        assertThat(result.width()).isEqualTo(800);
    }

    @Test
    @DisplayName("800px 이하 이미지는 확대하지 않는다")
    void 작은이미지_확대안함() throws Exception {
        ThumbnailOptimizer.Result result = ThumbnailOptimizer.optimize(png(400, 200, false));

        assertThat(result.width()).isEqualTo(400);
        assertThat(result.height()).isEqualTo(200);
    }

    /** 사진·스크린샷처럼 압축이 잘 안 되는(노이즈가 있는) 이미지. transparent면 위쪽 절반을 투명하게 만든다 */
    private static byte[] png(int width, int height, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = transparent && y < height / 2 ? 0 : 0xFF;
                int r = (x * 255 / width + random.nextInt(40)) & 0xFF;
                int g = (y * 255 / height + random.nextInt(40)) & 0xFF;
                int b = ((x + y) * 255 / (width + height) + random.nextInt(40)) & 0xFF;
                image.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return ImmutableImage.wrapAwt(image).bytes(PngWriter.MaxCompression);
    }
}
