package com.dlab.common.storage;

/**
 * 파일 저장소.
 *
 * <p><b>인터페이스로 감싸는 이유</b>가 하나다 — 지금은 로컬 디스크지만 운영은 S3다.
 * 도메인이 저장 방식을 알면 나중에 갈아끼울 때 도메인을 건드리게 된다.
 */
public interface FileStorage {

    /**
     * @param key   저장 키. 경로처럼 생겼지만 구현체가 해석한다
     * @param bytes 파일 본문
     * @return 실제 저장 키(구현체가 접두사를 붙일 수 있다)
     */
    String put(String key, byte[] bytes, String contentType);
}
