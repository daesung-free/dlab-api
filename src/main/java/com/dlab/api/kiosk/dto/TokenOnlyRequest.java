package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 파라미터가 토큰뿐인 요청 (getStdInfoList · getDlabList · getStudyAreaInfo). */
public record TokenOnlyRequest(
        @JsonProperty("token") String token
) implements KioskRequest {
}
