package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 키오스크 요청 공통.
 *
 * <p><b>토큰이 헤더가 아니라 본문에 온다</b>(`Authorization` 아님) — DSA가 그렇게 받았고
 * 키오스크 코드는 수정하지 않기로 했다. 그래서 Security 필터에서 처리하지 못하고
 * 컨트롤러가 직접 검증한다({@code SecurityConfig}의 {@code /kiosk/**}는 permitAll).
 */
public interface KioskRequest {

    @JsonProperty("token")
    String token();
}
