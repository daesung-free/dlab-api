package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 카드번호 + 월(`yyyy-MM`) 요청 (getRequestListStd). */
public record RfidMonthRequest(
        @JsonProperty("token") String token,
        @JsonProperty("rfid_no") String rfidNo,
        @JsonProperty("month") String month
) implements KioskRequest {
}
