package com.dlab.api.kiosk;

/**
 * DSA 호환 응답 코드. <b>키오스크가 이 숫자로 분기하므로 값을 바꾸면 안 된다.</b>
 *
 * <p>주의: 코드가 <b>최상위와 {@code data} 내부 양쪽에 흩어져</b> 온다.
 * 원본 DSA가 그렇게 응답했고 키오스크가 양쪽을 다 뒤지도록 구현돼 있어서,
 * 우리도 같은 위치로 내려줘야 한다({@link #atTopLevel()} 참고).
 *
 * <p>계약 상세는 {@code docs/dsa-compat.md} §4.
 */
public enum DsaCode {

    SUCCESS(0, "정상 처리되었습니다.", true),

    /** 토큰 만료. 키오스크가 refreshToken으로 재시도한다. */
    TOKEN_EXPIRED(910, "토큰이 만료되었습니다.", true),

    /** 파라미터 오류. 규격서 전 엔드포인트 공통 실패 코드({@code Invalid paramater}). */
    INVALID_PARAMETER(901, "Invalid paramater", true),

    /**
     * 카드번호 오류(등록되지 않은 RFID) 또는 구역코드 오류.
     * 규격서가 엔드포인트별로 같은 {@code 101}을 다른 의미로 쓴다.
     */
    INVALID_KEY(101, "존재하지 않는 코드입니다.", true),

    /** 대상 월 오류({@code getRequestListStd}). */
    INVALID_MONTH(102, "조회할 수 없는 대상 월입니다.", true),

    /**
     * 좌석이 이미 사용 중({@code setSeatChgProc}).
     *
     * <p>규격서가 이 엔드포인트의 오류코드를 명시하지 않았다. 101~200이 "요청 명령별 오류"
     * 구간이라 그 안에서 잡았다. <b>키오스크는 {@code code == 0}만 보고 분기하므로</b>
     * 정확한 값은 로그·운영용이고, 실제 동작에는 영향이 없다.
     */
    SEAT_OCCUPIED(103, "이미 사용 중인 좌석입니다.", true),

    /** 이미 조퇴 처리된 학생의 재태깅. 차단하고 이상 로그를 남긴다. */
    ALREADY_LEFT_EARLY(121, "이미 조퇴 처리되었습니다.", true),

    /** 승인 내역 없음. 이 메시지는 키오스크가 학생에게 그대로 노출한다. */
    NO_APPROVAL(130, "승인된 내역이 없습니다.", true),

    /** 학습 시간대가 아님. 거부하고 이상 로그를 남긴다. */
    OUTSIDE_STUDY_HOURS(122, "학습 시간이 아닙니다.", false),

    /**
     * 시간표가 없어 등원·하원 자동판별이 불가(주말·공휴일).
     * 키오스크가 사용자에게 선택지를 띄우고 {@code con_gn}을 실어 <b>재호출</b>한다.
     * 서버는 그 사이 상태를 들고 있지 않는다(stateless 2-phase).
     */
    NO_TIMETABLE(113, "하원/외출을 선택해 주세요.", false),

    /** 조퇴 + 사유외출 둘 다 선택 가능. */
    CHOICE_EARLY_LEAVE_OR_EXCUSED_OUTING(126, "조퇴 또는 사유외출을 선택해 주세요.", false),

    /** 조퇴만 선택 가능. */
    CHOICE_EARLY_LEAVE(128, "조퇴를 선택해 주세요.", false),

    /** 사유외출만 선택 가능. */
    CHOICE_EXCUSED_OUTING(129, "사유외출을 선택해 주세요.", false);

    private final int value;
    private final String defaultMessage;
    private final boolean topLevel;

    DsaCode(int value, String defaultMessage, boolean topLevel) {
        this.value = value;
        this.defaultMessage = defaultMessage;
        this.topLevel = topLevel;
    }

    public int value() {
        return value;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * 최상위 {@code code}로 내려야 하는지 여부.
     * {@code false}면 {@code data} 내부에 실어야 한다 — 위치가 바뀌면 키오스크가 못 읽는다.
     */
    public boolean atTopLevel() {
        return topLevel;
    }
}
