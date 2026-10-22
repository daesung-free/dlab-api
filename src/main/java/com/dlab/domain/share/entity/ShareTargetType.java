package com.dlab.domain.share.entity;

/**
 * 공유 토큰이 가리키는 대상.
 *
 * <p>★ <b>대상별로 테이블을 나누지 않는다.</b> 안내문·대기자가 같은 모양이다(무엇을
 * 가리키는가 + 언제까지 유효한가). 나누면 랜덤 생성·만료 판정·조회 경로를 대상 수만큼
 * 다시 짜게 되고, 한 곳만 고치면 다른 쪽이 조용히 다르게 동작한다.
 *
 * <p>⚠️ 토큰 하나는 <b>한 대상만</b> 가리킨다. 조회할 때 종류를 함께 대조할 것 —
 * 대기자 토큰을 안내문 경로에 넣으면 같은 숫자의 공지가 열린다.
 */
public enum ShareTargetType {
    /** 공개 안내문 — {@code notice.id} */
    NOTICE,
    /** 대기자 응답 — {@code admission_reservation.id} */
    ADMISSION_RESERVATION
}
