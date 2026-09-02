package com.dlab.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 홈페이지 입학예약 연동 자격증명.
 *
 * <p><b>DB가 아니라 설정에 둔다.</b> 검증식이 {@code MD5(yyyyMMdd + secret)}라 평문으로
 * 들고 있어야 하는데, 하나뿐인 값을 표로 만들 이유가 없고 환경변수면 저장소 백업·복제에
 * 실리지 않는다. 값은 커밋에 넣지 말 것(CLAUDE.md §8).
 *
 * <p>설정이 없으면 인증이 전부 거부된다 — 기동 시 경고를 남긴다.
 * 조용히 통과시키면 지원자 정보가 무인증으로 열린다.
 */
@Slf4j
@Getter
@Component
public class HomepageProperties {

    private final String clientId;
    private final String secret;
    private final String service;

    public HomepageProperties(
            @Value("${homepage.integration.client-id:}") String clientId,
            @Value("${homepage.integration.secret:}") String secret,
            @Value("${homepage.integration.service:dlab}") String service) {
        this.clientId = clientId;
        this.secret = secret;
        this.service = service;
    }

    public String clientId() {
        return clientId;
    }

    public String secret() {
        return secret;
    }

    public String service() {
        return service;
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && secret != null && !secret.isBlank();
    }

    @PostConstruct
    void warnIfMissing() {
        if (!isConfigured()) {
            log.warn("⚠️ 홈페이지 입학예약 연동 자격증명이 비어 있다 — /dlab/** 인증이 전부 거부된다. "
                    + "HOMEPAGE_CLIENT_ID·HOMEPAGE_SECRET 환경변수를 확인할 것");
        }
    }
}
