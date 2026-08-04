package com.dlab.common.security;

import com.dlab.common.exception.ErrorCode;

/**
 * JWT 검증 실패. 만료와 위조를 구분한다 —
 * 앱 인터셉터가 401을 받으면 refresh를 시도하는데(A-C1), 위조 토큰까지 재시도하면
 * 무의미한 왕복이 생기고 로그에서 공격 시도를 구분할 수 없다.
 */
public class JwtAuthenticationException extends RuntimeException {

    public enum Reason {
        /** 만료 — 클라이언트가 refresh로 재시도해야 한다. */
        EXPIRED(ErrorCode.UNAUTHORIZED),
        /** 서명 불일치·형식 오류 — 재시도 대상이 아니다. */
        INVALID(ErrorCode.UNAUTHORIZED);

        private final ErrorCode errorCode;

        Reason(ErrorCode errorCode) {
            this.errorCode = errorCode;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }

    private final Reason reason;

    public JwtAuthenticationException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public ErrorCode getErrorCode() {
        return reason.errorCode();
    }
}
