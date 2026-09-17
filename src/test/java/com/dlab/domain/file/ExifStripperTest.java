package com.dlab.domain.file;

import com.dlab.domain.file.service.ExifStripper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXIF 제거.
 *
 * <p>지키려는 것 — <b>촬영 메타가 남지 않을 것</b>, <b>대상이 아닌 형식은 건드리지 말 것</b>,
 * <b>깨진 파일에도 터지지 말 것</b>.
 */
class ExifStripperTest {

    @Test
    @DisplayName("★ JPEG 의 EXIF 가 사라진다 — GPS 가 실려 오는 경로가 실제로 있다")
    void removesExifFromJpeg() throws IOException {
        byte[] withExif = jpegWithExif();
        assertThat(containsExifMarker(withExif)).isTrue();

        byte[] stripped = ExifStripper.strip(withExif, "image/jpeg");

        assertThat(containsExifMarker(stripped)).isFalse();
        // 그림 자체는 남아야 한다
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(stripped))).isNotNull();
    }

    @Test
    @DisplayName("PDF·PNG 는 건드리지 않는다 — 다시 인코딩하면 잃는 것이 더 크다")
    void leavesOtherTypesAlone() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46};
        assertThat(ExifStripper.strip(pdf, "application/pdf")).isSameAs(pdf);
        assertThat(ExifStripper.strip(pdf, "image/png")).isSameAs(pdf);
    }

    @Test
    @DisplayName("★ 깨진 이미지여도 예외를 던지지 않는다 — 업로드 자체가 막히면 더 나쁘다")
    void brokenImageFallsBackToOriginal() {
        byte[] broken = {(byte) 0xFF, (byte) 0xD8, 0x00, 0x01, 0x02};
        assertThat(ExifStripper.strip(broken, "image/jpeg")).isSameAs(broken);
    }

    /** APP1(Exif) 세그먼트를 손으로 끼워 넣은 JPEG. */
    private byte[] jpegWithExif() throws IOException {
        byte[] plain = plainJpeg();
        // "Exif\0\0" + TIFF 헤더(little endian) + 방향 태그 1개
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
                'E', 'x', 'i', 'f', 0x00, 0x00,
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,                                  // 항목 1개
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00,                      // Orientation = 1
                0x00, 0x00, 0x00, 0x00
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                 // SOI
        out.write(app1);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    private byte[] plainJpeg() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private boolean containsExifMarker(byte[] data) {
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 'E' && data[i + 1] == 'x' && data[i + 2] == 'i' && data[i + 3] == 'f') {
                return true;
            }
        }
        return false;
    }
}
