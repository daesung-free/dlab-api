package com.dlab.integration.zyxel;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Nebula 목업 — 로그만 남긴다.
 *
 * <p>⚠️ <b>이대로 운영에 올라가면 와이파이가 실제로는 열리지도 닫히지도 않는데 API 는
 * 성공을 돌려준다.</b> 기동 시 경고를 남기는 이유다 — {@code LoggingSmsSender}와 같은 상황이다.
 *
 * <p>{@code zyxel.nebula.api-key} 를 넣으면 {@link NebulaVoucherClient} 가
 * {@code realNebulaClient} 로 올라오고 이 목업은 물러난다.
 *
 * <h2>★ 가짜 코드라도 돌려준다</h2>
 * {@code null} 을 주면 신청 건에 코드가 안 남아 <b>차단 경로가 "코드가 없다"로 실패</b>한다 —
 * 목업 때문에 실패가 생기면 진짜 실패와 구분되지 않는다. 형식만 같은 6자리를 만들어 준다.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "realNebulaClient")
public class LoggingNebulaClient implements NebulaClient {

    @PostConstruct
    void warn() {
        log.warn("⚠️ Nebula 목업이 활성화됐다 — 와이파이가 실제로 제어되지 않는다. "
                + "zyxel.nebula.api-key 와 지점별 사이트 ID 를 넣으면 실제 연동으로 바뀐다");
    }

    @Override
    public String assign(String siteId, int durationMinutes) {
        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
        log.info("[Nebula 목업] 해제: site={}, 분={}, 배정코드={}", siteId, durationMinutes, code);
        return code;
    }

    @Override
    public void revoke(String siteId, String voucherCode) {
        log.info("[Nebula 목업] 차단: site={}, code={}", siteId, voucherCode);
    }
}
