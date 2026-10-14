package com.dlab.domain.notification.entity;

/** 놓치면 안 되는 알림은 카카오 알림톡, 일반 알림은 FCM Push. */
public enum NotificationChannel {
    /** 카카오 알림톡. 템플릿 사전심사(E-5)가 필요하다. */
    KAKAO_ALIMTALK,

    /**
     * 문자(SMS).
     *
     * <h2>★ 알림톡과 다른 점은 「심사가 없다」는 것이다</h2>
     * 카카오는 승인된 템플릿 코드만 받지만 문자는 <b>본문을 그대로</b> 보낸다. 그래서
     * {@code reviewStatus = NOT_REQUIRED} 로 두면 {@code canSend()} 를 바로 통과한다.
     *
     * <h2>첫 사용처는 긴급출입이다</h2>
     * 출입문 비밀번호 제공·07:05 출근 미확인 2종이 <b>문자로 나가기로 확정</b>됐다
     * (2026-10-14 디랩 회신). 알림톡 심사를 기다릴 수 없는 성격의 알림이다.
     *
     * <p>⚠️ 발송은 <b>알림톡과 같은 알리고 계정·같은 발신번호</b>를 쓴다 — 연결부를
     * 둘로 만들지 말 것.
     */
    SMS,

    FCM_PUSH
}
