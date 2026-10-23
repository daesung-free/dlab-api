package com.dlab.domain.notification.entity;

/**
 * 직원 수동 발송의 대상 범위 (디랩 9/28 「지점 → 반 → 학생 3단계 발송 범위」).
 *
 * <p>⚠️ <b>대기자({@code WAITLIST})를 미리 넣지 않았다.</b> 입학 대기자 도메인이 아직
 * 없고(F-4.2-1 이 대성전산 입학예약폼 대기), 특강 대기자({@code ApplicationStatus.WAITLISTED})
 * 는 있으나 디랩 문구 「○○ 학생 등록이 가능합니다」가 어느 쪽인지 확정되지 않았다.
 * 값만 미리 두면 화면에 뜨는데 고르면 아무도 안 나온다.
 *
 * <p>★ <b>나중에 축을 더할 수 있는 모양</b>으로 둔다 — 범위를 푸는 곳이
 * {@code ManualNotificationService} 한 군데라, 값을 하나 더하고 그 switch 에 분기를
 * 더하면 끝난다. 스키마는 CHECK 제약뿐이라 enum 타입 변경도 필요 없다.
 */
public enum ManualSendScope {

    /** 지점 전체 재원생. */
    BRANCH,

    /** 반 하나. */
    CLASS,

    /** 직원이 고른 학생들. */
    SELECTED
}
