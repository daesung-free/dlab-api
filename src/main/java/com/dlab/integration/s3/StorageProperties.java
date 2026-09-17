package com.dlab.integration.s3;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 파일 저장소 설정 (application.yml {@code storage.*}).
 *
 * <p><b>버킷은 웹/앱이 아니라 공개·비공개로 나눈다.</b> 어느 클라이언트가 올렸는지는
 * 접근 범위와 무관하다 — 공지 이미지는 앱에서 올려도 공개고, 성적표는 웹에서 올려도
 * 비공개다. 그래서 클라이언트는 버킷 이름을 알 필요가 없고 API 만 둘로 나뉜다.
 *
 * <p>⚠️ <b>값이 비어도 앱은 뜬다.</b> 자격증명이 없는 환경(로컬·CI)에서 앱 전체가 안 뜨는
 * 쪽이 더 나쁘다. 대신 {@link S3FileStorage}가 기동 시 경고를 남기고, 실제로 올리려 하면
 * 그때 실패한다 — 조용히 성공한 척하면 "올렸는데 파일이 없는" 상태가 된다.
 *
 * @param publicBucket  공개 버킷. ⚠️ 이 버킷도 S3 는 닫혀 있다 — CloudFront(OAC)만 읽는다
 * @param publicBaseUrl CloudFront 배포 도메인. 공개 파일 URL 의 앞부분이 된다
 * @param presignTtl    비공개 파일 presigned URL 유효시간. <b>길게 잡지 말 것</b> —
 *                      그 주소는 로그인 없이 열리므로 전달되면 그대로 새어 나간다
 * @param maxFileSize   업로드 1건 상한. 입학상담 첨부 기준(10MB)에 맞춘다
 */
@ConfigurationProperties(prefix = "storage")
public record StorageProperties(
        String region,
        String publicBucket,
        String privateBucket,
        String publicBaseUrl,
        String accessKey,
        String secretKey,
        Duration presignTtl,
        DataSize maxFileSize) {

    public boolean configured() {
        return notBlank(publicBucket) && notBlank(privateBucket)
                && notBlank(accessKey) && notBlank(secretKey);
    }

    public Duration presignTtlOrDefault() {
        return presignTtl == null ? Duration.ofMinutes(5) : presignTtl;
    }

    public long maxFileSizeBytes() {
        return maxFileSize == null ? DataSize.ofMegabytes(10).toBytes() : maxFileSize.toBytes();
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
