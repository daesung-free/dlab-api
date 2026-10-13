package com.dlab.integration.premium;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 더프리미엄(대성전산) 성적표 API 설정.
 *
 * <p>비어 있어도 앱은 뜬다 — 키가 없으면 클라이언트 빈이 아예 올라오지 않는다.
 * <b>자격증명이 없다고 다른 기능까지 멈추면 안 된다</b>(Nebula 와 같은 규약).
 *
 * <h2>⚠️ 값을 커밋하지 말 것</h2>
 * 키는 <b>서버 환경변수로만</b> 둔다(§8). 평문 메일로 수령한 값이라 운영 전환 때
 * 교체를 요청해 둔 상태다.
 *
 * @param baseUrl    개발서버 주소. <b>아직 받지 못했다</b>
 * @param apiKey     {@code X-API-KEY}
 * @param secretKey  서명 계산에만 쓴다. <b>요청에 절대 실리지 않는다</b>
 * @param retryDays  성적표가 안 올라왔을 때 며칠까지 재시도할지.
 *                   ★ <b>404 하나로 「미발행」과 「없음」이 뭉쳐 있어</b> 시간으로 가를 수밖에 없다.
 *                   발행 소요일을 회신받기 전까지의 잠정값이라 <b>코드에 박지 않는다</b>
 */
@ConfigurationProperties(prefix = "premium.report")
public record PremiumProperties(String baseUrl, String apiKey, String secretKey,
                                Integer retryDays, Integer callIntervalMillis) {

    /** 회신 전까지 보수적으로 잡는다 — 제한을 모르는 채 800건을 몰아치면 막힌다 */
    private static final int DEFAULT_CALL_INTERVAL_MILLIS = 700;
    private static final int DEFAULT_RETRY_DAYS = 14;

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank()
                && secretKey != null && !secretKey.isBlank()
                && baseUrl != null && !baseUrl.isBlank();
    }

    public int retryDaysOrDefault() {
        return retryDays == null || retryDays <= 0 ? DEFAULT_RETRY_DAYS : retryDays;
    }

    public int callIntervalMillisOrDefault() {
        return callIntervalMillis == null || callIntervalMillis < 0
                ? DEFAULT_CALL_INTERVAL_MILLIS : callIntervalMillis;
    }
}
