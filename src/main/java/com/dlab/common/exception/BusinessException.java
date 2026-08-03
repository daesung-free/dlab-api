package com.dlab.common.exception;

import lombok.Getter;

/**
 * 도메인 규칙 위반 시 던지는 예외. GlobalExceptionHandler가 공통 실패 응답으로 변환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
