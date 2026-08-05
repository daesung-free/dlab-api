package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 구역 지정 요청 (getStudyAreaSeatInfo · getStudyAreaSeatState). */
public record AreaRequest(
        @JsonProperty("token") String token,
        @JsonProperty("area_cd") String areaCd
) implements KioskRequest {
}
