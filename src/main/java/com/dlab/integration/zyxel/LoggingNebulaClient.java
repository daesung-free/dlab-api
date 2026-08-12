package com.dlab.integration.zyxel;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Nebula 목업 — 로그만 남긴다.
 *
 * <p>⚠️ <b>이대로 운영에 올라가면 와이파이가 실제로는 열리지도 닫히지도 않는데 API는
 * 성공을 돌려준다.</b> 기동 시 경고를 남기는 이유다 — {@code LoggingSmsSender}와 같은 상황이다.
 *
 * <p>실제 구현체를 {@code realNebulaClient}라는 이름으로 등록하면 이 목업은 물러난다
 * — {@code LoggingSmsSender}와 같은 방식이다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "realNebulaClient")
public class LoggingNebulaClient implements NebulaClient {

    @PostConstruct
    void warn() {
        log.warn("⚠️ Nebula 목업이 활성화됐다 — 방화벽이 실제로 제어되지 않는다. "
                + "제어 단위(E-1) 확정 후 NebulaClient 구현체를 등록할 것");
    }

    @Override
    public void allow(String siteId, String target) {
        log.info("[Nebula 목업] 해제: site={}, target={}", siteId, target);
    }

    @Override
    public void block(String siteId, String target) {
        log.info("[Nebula 목업] 차단: site={}, target={}", siteId, target);
    }
}
