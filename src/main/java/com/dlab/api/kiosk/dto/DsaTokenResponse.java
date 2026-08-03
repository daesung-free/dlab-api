package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * {@code /auth/token}·{@code /auth/refreshToken} 응답.
 *
 * <p><b>이 엔드포인트만 {@link DsaResponse} 껍데기를 쓰지 않는다.</b>
 * 키오스크가 {@code data} 배열이 아니라 <b>최상위에서</b> {@code token}·{@code refreshToken}을
 * 꺼내가기 때문이다. 다른 엔드포인트와 형태가 다른 게 의도된 것이다.
 */
@JsonPropertyOrder({"code", "message", "token", "refreshToken"})
public record DsaTokenResponse(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("token") String token,
        @JsonProperty("refreshToken") String refreshToken
) {

    public static DsaTokenResponse of(String token, String refreshToken) {
        return new DsaTokenResponse(0, "정상 처리되었습니다.", token, refreshToken);
    }
}
