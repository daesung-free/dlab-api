package com.dlab.common.verification;

/**
 * 인증번호 발송 창구.
 *
 * <p><b>구현체를 벤더 이름으로 만들지 않았다.</b> 발송 수단이 아직 정해지지 않았기 때문이다 —
 * SMS 업체도, 카카오 알림톡 발신프로필(E-5)도 미확정이고 알림톡은 사전심사까지 남았다.
 * 확정되면 {@code integration/<벤더명>/}에 구현체를 만들고 이 인터페이스만 갈아끼운다
 * (CLAUDE.md §6-2 "연동은 핵심 도메인 완성 후").
 *
 * <p>지금은 {@link LoggingSmsSender}가 로그로만 남긴다.
 */
public interface SmsSender {

    /**
     * 인증번호를 보낸다.
     *
     * <p>실패를 예외로 던진다 — 조용히 무시하면 사용자는 오지 않는 문자를 기다린다.
     */
    void sendVerificationCode(String phone, String code);
}
