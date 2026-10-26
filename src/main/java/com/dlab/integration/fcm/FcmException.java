package com.dlab.integration.fcm;

/**
 * FCM 발송 실패.
 *
 * <p>발송 경로가 이 예외를 잡아 이력을 {@code FAILED} 로 남긴다 —
 * 던지지 않으면 <b>안 보낸 것이 성공으로 기록된다.</b>
 */
public class FcmException extends RuntimeException {

    public FcmException(String message) {
        super(message);
    }

    public FcmException(String message, Throwable cause) {
        super(message, cause);
    }
}
