package com.dlab.domain.firewall.entity;

/**
 * 해제 상태 (F-4.11-10).
 *
 * <p><b>승인 상태와 다르다.</b> {@code approval_request.status}는 "학부모가 승인했나"이고,
 * 승인됐어도 해제 시간이 지나면 와이파이는 닫혀야 한다.
 *
 * <p>시각({@code unlockEndAt})만으로 판별하지 않는 이유는 <b>"만료 처리를 했는가"</b>와
 * 구분해야 하기 때문이다 — 그래야 스케줄러가 무엇을 아직 안 보냈는지 알 수 있다.
 */
public enum UnlockStatus {

    /** 승인 전이거나 아직 시작하지 않음. */
    WAITING,

    ACTIVE,

    /** 만료돼 차단 처리까지 끝난 상태. */
    EXPIRED,

    CANCELED
}
