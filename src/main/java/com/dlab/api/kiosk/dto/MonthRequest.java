package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 월(`yyyy-MM`) 요청 (getMealApplyStdInfo). */
public record MonthRequest(
        @JsonProperty("token") String token,
        @JsonProperty("month") String month
) implements KioskRequest {
}
