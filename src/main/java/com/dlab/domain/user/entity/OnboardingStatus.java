package com.dlab.domain.user.entity;

import java.util.List;

/**
 * 학생 앱 온보딩 상태 (앱 요구사항 A-2).
 *
 * <p><b>서버의 이 값이 단일 진실</b>이고 앱은 여기 따라 화면을 분기한다(시트 A-2 명시).
 * 각 단계가 끝나지 않으면 다음 단계로 강제 라우팅된다.
 *
 * <h2>★ 승인 상태를 여기 넣지 않는다</h2>
 * "승인 대기 / 승인됨"은 {@link AccountStatus}({@code PENDING} → {@code ACTIVE})가 이미 들고 있다.
 * 온보딩에도 같은 사실을 두면 <b>두 값이 어긋났을 때 어느 쪽이 맞는지 알 수 없다</b> —
 * 승인은 났는데 온보딩은 아직 대기인 계정이 생기고, 그때 앱이 무엇을 믿어야 할지 정해지지 않는다.
 *
 * <p>두 축을 함께 보면 상태가 하나로 정해진다:
 * <ul>
 *   <li>{@code PENDING} + {@code REGISTERED} — 가입 신청함, 승인 대기</li>
 *   <li>{@code ACTIVE} + {@code REGISTERED} — 승인됨, OT 전</li>
 *   <li>{@code ACTIVE} + {@code OT_DONE} 이후 — 온보딩 진행 중</li>
 * </ul>
 *
 * <h2>사람에 붙는다</h2>
 * 앱 계정({@code account.student_id})이 등록 건이 아니라 사람에 붙으므로 온보딩도 같다.
 * ⚠️ 다만 <b>재등록(삼수) 시 OT를 다시 받는지는 미확인</b>이다 — 다시 받아야 한다면
 * 기수 시작 시 {@code REGISTERED}로 되돌리는 절차가 따로 필요하다.
 */
public enum OnboardingStatus {

    /** 가입 직후. 관리자 승인은 {@link AccountStatus}가 따로 본다. */
    REGISTERED,

    /** 대면 OT 완료. 관리자가 체크한다. */
    OT_DONE,

    /** 학부모 계정 연동 완료(최대 1인). 학부모가 학생 고유ID로 연결하면 넘어간다. */
    PARENT_LINKED,

    /** 정기일정 최초 등록 완료. */
    SCHEDULE_SET,

    /** 온보딩 끝. 앱 전 기능 사용 가능. */
    ACTIVE;

    private static final List<OnboardingStatus> ORDER =
            List.of(REGISTERED, OT_DONE, PARENT_LINKED, SCHEDULE_SET, ACTIVE);

    /**
     * 다음 단계. 이미 {@link #ACTIVE}면 자기 자신.
     *
     * <p>단계를 <b>건너뛰지 못하게</b> 여기서만 전진시킨다. 각 단계 완료 처리가 제각각
     * 값을 대입하면 OT를 안 했는데 {@code SCHEDULE_SET}인 계정이 생긴다.
     */
    public OnboardingStatus next() {
        int i = ORDER.indexOf(this);
        return i < 0 || i == ORDER.size() - 1 ? ACTIVE : ORDER.get(i + 1);
    }

    /** 이 단계가 {@code other}까지 왔는지. 라우팅 판정에 쓴다. */
    public boolean reached(OnboardingStatus other) {
        return ORDER.indexOf(this) >= ORDER.indexOf(other);
    }

    public boolean isCompleted() {
        return this == ACTIVE;
    }
}
