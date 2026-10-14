package com.dlab.domain.notification.entity;

/**
 * 알림 수신자 (앱 요구사항 A-C3 매핑표의 수신자 축).
 *
 * <p><b>{@link #ROUTED}가 따로 있는 이유</b> — 승인 요청은 수신자가 고정이 아니라
 * {@code approval_item.approver_type}에 따라 실행 시점에 갈린다. 여기 {@code PARENT}로
 * 박아두면 승인 주체 매트릭스(I-12)가 바뀔 때 템플릿까지 같이 고쳐야 한다.
 */
public enum RecipientType {
    STUDENT,
    PARENT,
    BOTH,
    /** 승인 주체 설정에 따라 실행 시점에 결정된다. */
    ROUTED,

    /**
     * 관리자(직원·담당선생님).
     *
     * <h2>★ 학생·학부모가 아니라 「우리 쪽」에 가는 알림이 생겼다</h2>
     * 긴급출입 2종(출입문 비밀번호 제공 · 07:05 출근 미확인)이 그것이다 — 받는 사람이
     * <b>지점 담당자</b>라 기존 네 값({@code STUDENT}·{@code PARENT}·{@code BOTH}·
     * {@code ROUTED})으로는 표현되지 않는다.
     *
     * <p>⚠️ {@code ROUTED} 로 때우지 말 것. 그건 <b>승인 주체 설정</b>을 따라가는 값이라
     * 학생이 「학부모 우선」을 골라 두면 <b>관리자 알림이 학부모에게 간다.</b>
     *
     * <p>누구에게 보낼지는 <b>권한으로 정한다</b> — 긴급출입은
     * {@code EMERGENCY_ACCESS_ALERT} 메뉴를 명시적으로 받은 계정만 받는다. 역할 기본값으로
     * 정하면 <b>그 메뉴가 없는 계정에까지</b> 번호가 나간다.
     */
    ADMIN
}
