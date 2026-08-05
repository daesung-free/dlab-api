package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code setSeatChgProc} 요청 (규격서 3.23). */
public record SeatChangeRequest(
        @JsonProperty("token") String token,
        @JsonProperty("rfid_no") String rfidNo,
        @JsonProperty("seat_cd") String seatCd
) implements KioskRequest {
}
