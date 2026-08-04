package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code POST /auth/token} 요청.
 *
 * <p>필드명이 snake_case인 건 키오스크가 그렇게 보내기 때문이다.
 * 전역 네이밍 전략으로 바꾸면 {@code /api/v1} 응답까지 같이 바뀌므로
 * 이 구획 DTO에만 {@code @JsonProperty}로 명시한다.
 *
 * @param acadCd   지점코드. 참고용 — 실제 지점 판별은 clientId로 한다
 * @param clientId 키오스크 식별자
 * @param secretId {@code MD5(yyyyMMdd + secret)}
 * @param service  현재 확인된 값은 {@code kiosk} 뿐이다
 */
public record DsaTokenRequest(
        @JsonProperty("acad_cd") String acadCd,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("secret_id") String secretId,
        @JsonProperty("service") String service
) {
}
