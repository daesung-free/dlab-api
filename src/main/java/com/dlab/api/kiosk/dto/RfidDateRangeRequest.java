package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 카드번호 + 기간 요청 (getStdAttendState). */
public record RfidDateRangeRequest(
        @JsonProperty("token") String token,
        @JsonProperty("rfid_no") String rfidNo,
        @JsonProperty("st_dt") String startDate,
        @JsonProperty("ed_dt") String endDate
) implements KioskRequest {
}
