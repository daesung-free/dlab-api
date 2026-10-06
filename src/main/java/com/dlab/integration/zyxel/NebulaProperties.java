package com.dlab.integration.zyxel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nebula 연동 설정.
 *
 * <p>★ <b>사이트 ID 는 여기 없다.</b> 지점마다 달라 한 칸에 담기지 않는다 —
 * {@code branch_config.nebula_site_id} 에 두고 관리자 화면에서 넣는다
 * (키오스크 자격증명과 같은 자리다).
 *
 * <p>비어 있어도 앱은 뜬다. 키가 없으면 목업이 그대로 남아 <b>와이파이가 제어되지 않지만
 * 다른 기능은 멈추지 않는다</b> — 자격증명이 없는 로컬·CI 를 위해서다.
 *
 * @param apiKey   요청 헤더 {@code X-ZyxelNebula-API-Key} 에 실린다. ⚠️ 커밋 금지
 * @param poolSize 지점별로 미리 만들어 둘 코드 수. 미사용 코드가 없을 때 이 수만큼 채운다
 */
@ConfigurationProperties(prefix = "zyxel.nebula")
public record NebulaProperties(String baseUrl, String apiKey, Integer poolSize) {

    private static final String DEFAULT_BASE_URL = "https://api.nebula.zyxel.com";
    private static final int DEFAULT_POOL_SIZE = 20;

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String baseUrlOrDefault() {
        return baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL;
    }

    public int poolSizeOrDefault() {
        return poolSize == null || poolSize <= 0 ? DEFAULT_POOL_SIZE : poolSize;
    }
}
