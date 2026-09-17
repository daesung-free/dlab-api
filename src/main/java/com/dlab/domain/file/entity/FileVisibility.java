package com.dlab.domain.file.entity;

/**
 * 파일 공개 범위. <b>버킷을 가르는 기준</b>이다.
 *
 * <p>업로드 시점에 정해지고 나중에 바뀌지 않는다 — 바꾸려면 다른 버킷으로 옮겨야 하는데,
 * 그 사이 기존 URL 이 깨진다. 잘못 올렸으면 지우고 다시 올린다.
 */
public enum FileVisibility {

    /** 공지 이미지·특강 홍보물 등. CloudFront 주소로 서빙되고 URL 이 고정된다. */
    PUBLIC,

    /**
     * 성적표·재학증명서·사유 증빙 등. 직접 접근이 막혀 있고 서버가 발급한
     * 짧은 presigned URL 로만 열린다.
     */
    PRIVATE
}
