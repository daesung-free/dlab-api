package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code POST /auth/refreshToken} 요청. */
public record DsaRefreshRequest(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("refreshToken") String refreshToken
) {
}
