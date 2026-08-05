package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 카드번호로 학생을 지정하는 요청 (getStdInfo · getParentHpList). */
public record RfidRequest(
        @JsonProperty("token") String token,
        @JsonProperty("rfid_no") String rfidNo
) implements KioskRequest {
}
