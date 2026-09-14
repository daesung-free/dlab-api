package com.dlab.domain.file.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * 사진에서 촬영 메타(EXIF)를 떼어낸다.
 *
 * <h2>왜 떼는가</h2>
 * 휴대폰으로 찍은 사진에는 <b>촬영 위치(GPS)·시각·기기 모델</b>이 그대로 들어 있다.
 * 학생이 사유 증빙으로 올린 영수증 사진 한 장에 집 좌표가 실려 오고, 그 파일은 담당
 * 직원이 열어본다. 발주처가 개인정보에 민감한 상태라(CLAUDE.md §7) 받는 쪽에서 지운다.
 *
 * <p><b>앱에서 떼면 되지 않느냐</b> — 앱·웹·나중에 생길 경로가 각자 구현해야 하고,
 * 한 곳만 빠뜨리면 그때부터 조용히 새어 들어온다. 들어오는 문 하나에서 막는다.
 *
 * <h2>★ 회전 정보도 EXIF 에 있다</h2>
 * 그래서 그냥 지우면 <b>세로로 찍은 사진이 눕는다.</b> 방향을 먼저 읽어 픽셀을 실제로
 * 돌린 뒤 메타 없이 다시 쓴다.
 *
 * <p>JPEG 만 다룬다. PNG·GIF 는 촬영 메타가 실리는 경우가 드물고, 다시 인코딩하면
 * 애니메이션·투명도가 깨진다. PDF 는 손대지 않는다 — 문서 구조를 다시 쓰는 일이라
 * 실패 위험이 이득보다 크다.
 */
@Slf4j
public final class ExifStripper {

    private ExifStripper() {
    }

    /**
     * @return 메타가 제거된 바이트. 대상이 아니거나 처리에 실패하면 <b>원본 그대로</b>
     *         돌려준다 — 업로드 자체가 막히는 것보다 낫다(실패는 로그로 남는다)
     */
    public static byte[] strip(byte[] original, String contentType) {
        if (!isJpeg(contentType)) {
            return original;
        }
        try {
            int orientation = readOrientation(original);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                return original;
            }
            BufferedImage rotated = rotate(image, orientation);

            ByteArrayOutputStream out = new ByteArrayOutputStream(original.length);
            // ImageIO 는 메타를 실어 보내지 않는다 — 다시 쓰는 것만으로 EXIF 가 사라진다
            if (!ImageIO.write(rotated, "jpg", out)) {
                return original;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("EXIF 제거 실패 — 원본을 그대로 올린다: {}", e.toString());
            return original;
        }
    }

    private static boolean isJpeg(String contentType) {
        return contentType != null
                && (contentType.equalsIgnoreCase("image/jpeg")
                    || contentType.equalsIgnoreCase("image/jpg"));
    }

    /**
     * EXIF 방향 태그(0x0112)를 읽는다. 없으면 1(정방향).
     *
     * <p>APP1 세그먼트 안의 TIFF 헤더를 직접 훑는다 — 이 값 하나 때문에 메타데이터
     * 라이브러리를 하나 더 들이지 않는다.
     */
    static int readOrientation(byte[] data) {
        for (int i = 2; i + 4 < data.length; ) {
            if ((data[i] & 0xFF) != 0xFF) {
                break;
            }
            int marker = data[i + 1] & 0xFF;
            int length = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 < data.length
                    && data[i + 4] == 'E' && data[i + 5] == 'x'
                    && data[i + 6] == 'i' && data[i + 7] == 'f') {
                return orientationInTiff(data, i + 10, length - 8);
            }
            if (marker == 0xDA) {   // 이미지 데이터 시작 — 여기부터는 메타가 없다
                break;
            }
            i += 2 + length;
        }
        return 1;
    }

    private static int orientationInTiff(byte[] d, int tiffStart, int length) {
        if (tiffStart + 8 > d.length) {
            return 1;
        }
        boolean bigEndian = d[tiffStart] == 'M';
        int ifdOffset = readInt(d, tiffStart + 4, bigEndian);
        int ifd = tiffStart + ifdOffset;
        if (ifd + 2 > d.length) {
            return 1;
        }
        int count = readShort(d, ifd, bigEndian);
        for (int e = 0; e < count; e++) {
            int entry = ifd + 2 + e * 12;
            if (entry + 12 > d.length || entry + 12 > tiffStart + length) {
                break;
            }
            if (readShort(d, entry, bigEndian) == 0x0112) {
                return readShort(d, entry + 8, bigEndian);
            }
        }
        return 1;
    }

    private static int readShort(byte[] d, int at, boolean bigEndian) {
        int a = d[at] & 0xFF;
        int b = d[at + 1] & 0xFF;
        return bigEndian ? (a << 8) | b : (b << 8) | a;
    }

    private static int readInt(byte[] d, int at, boolean bigEndian) {
        int a = d[at] & 0xFF;
        int b = d[at + 1] & 0xFF;
        int c = d[at + 2] & 0xFF;
        int e = d[at + 3] & 0xFF;
        return bigEndian ? (a << 24) | (b << 16) | (c << 8) | e
                : (e << 24) | (c << 16) | (b << 8) | a;
    }

    /** EXIF 방향값(1~8)대로 픽셀을 돌린다. 뒤집힌 값(2·4·5·7)은 거울상까지 포함한다. */
    private static BufferedImage rotate(BufferedImage src, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h,
                BufferedImage.TYPE_INT_RGB);

        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0);
                        t.translate(0, w); t.rotate(-Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { }
        }

        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
