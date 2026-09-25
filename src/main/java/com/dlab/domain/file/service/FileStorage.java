package com.dlab.domain.file.service;

import com.dlab.domain.file.entity.FileVisibility;
import java.io.InputStream;
import java.time.Duration;

/**
 * 파일 저장소 창구.
 *
 * <p><b>도메인은 S3 를 모른다.</b> 저장소가 바뀌어도(계정 이전·다른 벤더) 이 인터페이스
 * 뒤에서만 바뀐다 — CLAUDE.md §5 의 {@code integration} 분리 원칙이다.
 */
public interface FileStorage {

    /** 올린다. 같은 키가 있으면 덮어쓴다 — 키에 UUID 가 들어가므로 실제로는 겹치지 않는다. */
    void put(FileVisibility visibility, String objectKey, InputStream content, long size,
             String contentType);

    /**
     * 지운다.
     *
     * <p>⚠️ <b>첨부 삭제 경로에서 부르지 않는다.</b> 첨부는 soft delete 이고 원본은 남아야
     * 한다 — 보관기간이 지난 파일을 정리하는 작업에서만 쓴다.
     */
    void delete(FileVisibility visibility, String objectKey);

    /**
     * 비공개 파일을 볼 수 있는 임시 주소.
     *
     * <p><b>이 주소는 로그인 없이 열린다.</b> 전달되면 그대로 새어 나가므로 유효시간을
     * 짧게 준다.
     */
    String presignedGetUrl(String objectKey, Duration ttl);

    /** 공개 파일의 고정 주소(CloudFront). */
    String publicUrl(String objectKey);
}
