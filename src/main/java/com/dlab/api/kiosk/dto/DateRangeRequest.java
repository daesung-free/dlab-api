package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 기간 요청 (getPointStdList). 날짜는 `yyyy-MM-dd`. */
public record DateRangeRequest(
        @JsonProperty("token") String token,
        @JsonProperty("st_dt") String startDate,
        @JsonProperty("ed_dt") String endDate
) implements KioskRequest {
}
