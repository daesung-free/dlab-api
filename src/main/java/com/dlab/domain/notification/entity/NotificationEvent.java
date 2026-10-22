package com.dlab.domain.notification.entity;

/**
 * 알림 이벤트 코드. DB의 notification_template.event_code 와 1:1로 대응한다.
 * 채널 매핑과 문구는 DB 템플릿이 진실의 원천이고, 여기서는 종류만 정의한다.
 */
public enum NotificationEvent {
    /** 등원 기준시각까지 태깅 기록이 없는 무단 미등원 */
    MISSING_ATTENDANCE,

    // ── 출결 태깅 알림 (디랩 1016 문구 1~5번) ────────────────────────────────
    // 학부모에게만 간다 — "○○ 학생이 등원했습니다"를 학생 본인에게 보낼 이유가 없다.
    //
    // ★ 지각(LATE)도 등원 알림으로 나간다. 지각 전용 문구가 없는데 여기서 안 보내면,
    //   미등원 알림(기준시각)을 이미 받은 학부모가 자녀를 **결석으로 알고 끝낸다**.
    //   「지각 안내가 빠진 것이 맞는지」는 디랩 회신 대기고, 그때 문구가 따로 생기면
    //   이벤트를 하나 더 두면 된다.
    /** 등원 태깅 — <b>정시 등원만</b>. 지각은 아래 {@link #ATTENDANCE_LATE} 다 */
    ATTENDANCE_CHECK_IN,
    /**
     * 지각 태깅 (디랩 1019 회신으로 분리).
     *
     * <p>1016 에는 등원 알림에 합쳐 넣었다 — 지각 전용 문구가 없어서였다. 그 문구가
     * 왔으므로 분리한다.
     *
     * <p>⚠️ <b>사유지각도 이 알림이 나간다.</b> 1019 문구에 조건이 없다
     * (원장은 지각이고 키오스크 화면만 등원으로 뜬다). 학부모가 사유를 승인한 건에도
     * 「지각 등원」이 가므로, 나중에 가르게 되면 {@code AttendanceNotifier.eventOf} 한 줄이다.
     */
    ATTENDANCE_LATE,
    /** 하원 태깅 */
    ATTENDANCE_CHECK_OUT,
    /** 외출 태깅 — 사유 외출 포함 */
    ATTENDANCE_OUTING,
    /** 외출 복귀 태깅 */
    ATTENDANCE_RETURN,
    /** 조퇴 태깅 */
    ATTENDANCE_EARLY_LEAVE,
    /** 승인 요청 접수 — 학부모와 담당선생님에게 동시 발송 */
    APPROVAL_REQUEST_CREATED,
    /** 타임아웃 전 학부모 승인 */
    APPROVAL_APPROVED_BY_PARENT,
    /** 타임아웃 경과 후 담당선생님 승인 — "시간이 지나 담임이 승인" */
    APPROVAL_APPROVED_AFTER_TIMEOUT,
    /** 타임아웃 전 담당선생님이 먼저 승인 — "시간이 남았지만 담임이 먼저 승인" (위와 다른 문구) */
    APPROVAL_APPROVED_BEFORE_TIMEOUT,
    /** 학부모 무응답 → 1회 자동 재승인 요청 (0803 답변서) */
    APPROVAL_REMINDER,
    /** 재요청 후에도 무응답 → 직원에게 승인권 이양 */
    APPROVAL_HANDED_OVER,
    /** 우선 승인자가 직원이라 직원이 승인함 — 학부모에겐 통지만 나간다 */
    APPROVAL_APPROVED_BY_STAFF_PRIMARY,

    /** 관리자가 데스크에서 대리 처리. 담임 승인과 문구가 달라야 한다. */
    APPROVAL_APPROVED_BY_ADMIN,
    APPROVAL_REJECTED,
    // ── 설문 (F-4.6 "미응답자 독려") ─────────────────────────────────────────
    // 학생 본인에게 간다 — 설문에 답하는 사람이 학생이다.
    //
    // ⚠️ 두 이벤트를 하나로 합치지 말 것. 처음 받는 사람과 이미 한 번 받고도 안 낸 사람에게
    //   같은 문구가 가면 안 된다 — 독촉은 「아직 안 내셨습니다」로 시작해야 하고,
    //   첫 발송에 그 문구가 가면 받은 사람이 자기가 뭘 놓쳤다고 생각한다.
    /** 설문 대상자로 지정됨 — 첫 발송 */
    SURVEY_ASSIGNED,
    /** 미응답 독촉 — 여러 번 보낼 수 있다(중복 방지는 날짜 단위) */
    SURVEY_REMINDER,

    // ── 직원 수동 발송 (디랩 1016 문구 8~10번) ──────────────────────────────
    //
    // ★ 여기에만 있는 특징 — **트리거가 없다.** 다른 이벤트는 태깅·승인·배치가 부르지만
    //   이 셋은 직원이 때를 골라 보낸다(`notification_template.manual = true`).
    //   코드 어디에서도 자동으로 부르지 않는 것이 정상이다.
    //
    // ⚠️ 대기자 안내톡(1016 7번)은 넣지 않았다 — 입학 대기자 도메인이 없어(F-4.2-1 대기)
    //   보낼 대상을 고를 수 없다. 특강 대기자와 어느 쪽인지도 확정되지 않았다.
    /** 급식 신청 마감 안내 — "{월} 급식 신청이 {날짜}에 마감됩니다" */
    MEAL_APPLY_DEADLINE,
    /** 등록(교습비 납부) 기간 안내 */
    TUITION_REGISTRATION_NOTICE,
    /** 등록비 미납 안내 */
    TUITION_UNPAID_NOTICE,

    /** 학생이 상담을 예약함 — 담임에게 (F-4.11-4 "선택한 일정에 알림 연동") */
    CONSULT_RESERVED,
    /** 상담 예약 취소 — 담임이 빈 자리를 알아야 그 시간을 다시 쓴다 */
    CONSULT_CANCELED
}
