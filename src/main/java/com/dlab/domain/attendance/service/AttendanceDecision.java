package com.dlab.domain.attendance.service;

import com.dlab.api.kiosk.DsaCode;
import com.dlab.domain.attendance.entity.AttendanceEventType;

/**
 * 태깅 판정 결과 — <b>기록할 이벤트</b>이거나 <b>돌려줄 분기 코드</b> 둘 중 하나다.
 *
 * <p>둘을 한 객체에 담는 이유: 키오스크 계약상 "처리됨"과 "되물음"이 같은 엔드포인트의
 * 같은 응답 자리에서 갈린다. 분리하면 호출부가 두 경로를 조립해야 한다.
 *
 * @param event 원장에 기록할 출결 이벤트. 분기 코드일 때는 {@code null}
 * @param reportedAs 키오스크에 돌려줄 {@code att_gn}. 보통 {@code event}와 같지만
 *                   <b>사유지각은 갈린다</b> — {@link #of(AttendanceEventType, AttendanceEventType)} 참고
 * @param code  분기·거부 코드. 정상 처리일 때는 {@code null}
 * @param message 코드에 딸린 안내 문구. 키오스크가 <b>학생에게 그대로 노출</b>한다
 */
public record AttendanceDecision(
        AttendanceEventType event,
        AttendanceEventType reportedAs,
        DsaCode code,
        String message
) {

    public static AttendanceDecision of(AttendanceEventType event) {
        return new AttendanceDecision(event, event, null, null);
    }

    /**
     * 원장 기록과 키오스크 응답을 다르게 낸다.
     *
     * <p><b>사유지각이 유일한 경우다.</b> 학원은 화면에 "등원"으로 뜨길 원하지만
     * 원장에는 <b>지각으로 남아야 한다</b> — 사유가 있어도 늦게 온 건 사실이고,
     * 등원으로 덮으면 "몇 번 늦었나"에 답할 수 없으며 상벌점 규칙(I-5)이
     * 무단지각과 사유지각을 가릴 근거를 잃는다.
     *
     * <p>문구를 코드로 갈라 보내는 이유: 키오스크가 {@code att_gn} 한 글자로 화면 문구를
     * 고른다(그쪽 {@code AttendAction}에 {@code S=등원 A=지각}이 하드코딩돼 있다).
     * 우리가 내려보내는 {@code message}는 화면에 안 쓰인다.
     *
     * <p>⚠️ 이 경우 <b>키오스크 자체 DB와 우리 원장이 그 건에서 어긋난다.</b>
     * 키오스크는 출결을 이중 보관하는데 거기엔 등원으로 쌓인다.
     */
    public static AttendanceDecision of(AttendanceEventType event,
                                        AttendanceEventType reportedAs) {
        return new AttendanceDecision(event, reportedAs, null, null);
    }

    public static AttendanceDecision reject(DsaCode code) {
        return new AttendanceDecision(null, null, code, code.defaultMessage());
    }

    public static AttendanceDecision reject(DsaCode code, String message) {
        return new AttendanceDecision(null, null, code, message);
    }

    public boolean isAccepted() {
        return event != null;
    }
}
