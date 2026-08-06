package com.dlab.domain.facility.entity;

import com.dlab.domain.attendance.entity.AttendanceEventType;

/**
 * 좌석 재실 상태 — <b>배정 축과 별개인 두 번째 축</b>이다.
 *
 * <p>화면(`ReadingRoom.tsx`)이 두 축을 겹쳐 표시한다:
 * <ul>
 *   <li><b>배정 축</b> — 배정됨 / 미배정 / 사용중지</li>
 *   <li><b>재실 축</b> — 여기</li>
 * </ul>
 * 하나로 합치면 "배정됐지만 미등원"과 "애초에 빈자리"를 구분할 수 없다.
 *
 * <h2>저장하지 않고 출결에서 파생한다</h2>
 * 자리이탈 원장이 아직 없어(I-16 미확정) 그날 마지막 태깅으로 판정한다.
 * <b>키오스크 {@code getStudyAreaSeatState}와 같은 규칙을 써야 한다</b> —
 * 두 벌로 두면 같은 좌석이 키오스크와 관리자 화면에서 다르게 보인다.
 */
public enum SeatPresence {

    /** 재실. 등원·지각·복귀 뒤 그대로 있다. */
    PRESENT("S"),

    /** 외출 중. 오늘 다시 돌아온다. */
    OUT("D"),

    /** 배정은 됐는데 자리에 없다 — 미등원이거나 이미 하원·조퇴했다. */
    ABSENT("N"),

    /** 배정 자체가 없는 빈 좌석. */
    EMPTY("B");

    private final String dsaCode;

    SeatPresence(String dsaCode) {
        this.dsaCode = dsaCode;
    }

    /** DSA 호환 구획이 내려보내는 {@code state} 값. 바꾸면 키오스크 파싱이 깨진다. */
    public String dsaCode() {
        return dsaCode;
    }

    /**
     * 배정된 좌석의 재실 판정.
     *
     * <p><b>하원·조퇴는 공석이 아니라 {@link #ABSENT}다</b> — 오늘 더는 안 오는 자리이지
     * 남에게 줄 수 있는 빈자리가 아니다.
     *
     * @param lastEvent 그날 마지막 태깅. {@code null}이면 아직 안 왔다
     */
    public static SeatPresence of(AttendanceEventType lastEvent) {
        if (lastEvent == null) {
            return ABSENT;
        }
        return switch (lastEvent) {
            case CHECK_IN, LATE, RETURN -> PRESENT;
            case OUTING, EXCUSED_OUTING -> OUT;
            case CHECK_OUT, EARLY_LEAVE -> ABSENT;
        };
    }
}
