package com.dlab.domain.attendance.service;

import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.attendance.entity.AttendanceEventType;

/**
 * 태깅 판정 결과 — <b>기록할 이벤트</b>이거나 <b>돌려줄 분기 코드</b> 둘 중 하나다.
 *
 * <p>둘을 한 객체에 담는 이유: 키오스크 계약상 "처리됨"과 "되물음"이 같은 엔드포인트의
 * 같은 응답 자리에서 갈린다. 분리하면 호출부가 두 경로를 조립해야 한다.
 *
 * @param event 기록할 출결 이벤트. 분기 코드일 때는 {@code null}
 * @param code  분기·거부 코드. 정상 처리일 때는 {@code null}
 * @param message 코드에 딸린 안내 문구. 키오스크가 <b>학생에게 그대로 노출</b>한다
 */
public record AttendanceDecision(
        AttendanceEventType event,
        DsaCode code,
        String message
) {

    public static AttendanceDecision of(AttendanceEventType event) {
        return new AttendanceDecision(event, null, null);
    }

    public static AttendanceDecision reject(DsaCode code) {
        return new AttendanceDecision(null, code, code.defaultMessage());
    }

    public static AttendanceDecision reject(DsaCode code, String message) {
        return new AttendanceDecision(null, code, message);
    }

    public boolean isAccepted() {
        return event != null;
    }
}
