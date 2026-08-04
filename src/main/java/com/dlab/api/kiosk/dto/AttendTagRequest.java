package com.dlab.api.kiosk.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code setAttendStd} 요청 (규격서 3.14).
 *
 * @param tagDt 태깅 시각 {@code yyyy-MM-dd HH:mm:ss}. <b>키오스크가 보낸 값을 쓴다</b> —
 *              서버 시각으로 덮으면 네트워크 지연만큼 태깅 시각이 밀려 지각 판정이 뒤집힌다
 * @param conGn 학생이 고른 액션. 기본 NULL / 조퇴 {@code C} / 외출 {@code D} / 사유외출 {@code N}.
 *              규격서 목록엔 없지만 키오스크는 하원 {@code T}도 보낸다 —
 *              {@code code 113} 프롬프트가 "하원/외출"이기 때문이다
 */
public record AttendTagRequest(
        @JsonProperty("token") String token,
        @JsonProperty("rfid_no") String rfidNo,
        @JsonProperty("tag_dt") String tagDt,
        @JsonProperty("con_gn") String conGn
) implements KioskRequest {
}
