package com.dlab.common.exception;

import lombok.Getter;

/**
 * 도메인 규칙 위반 시 던지는 예외. GlobalExceptionHandler가 공통 실패 응답으로 변환한다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 오류를 고치는 데 필요한 값. <b>대부분 {@code null}이다.</b>
     *
     * <p>화면이 메시지 문자열을 파싱하지 않게 하려는 것이다 — 중복 등록에서 학번을 꺼내려면
     * {@code "이미 등록된 학생입니다. (2026-0031)"}에서 괄호를 뜯어야 했고,
     * <b>문구를 다듬는 순간 화면이 깨진다.</b>
     *
     * <p>싣는 기준은 <b>화면이 그걸로 뒤 동작을 할 수 있는가</b>다 —
     * "해당 학생으로 이동" 같은 것. 단순 안내에는 싣지 않는다.
     *
     * <p>⚠️ <b>개인정보를 담지 말 것.</b> 오류 응답은 로그·모니터링에 그대로 남는다.
     * 식별자(학번·id)까지가 한계이고 이름·연락처는 넣지 않는다.
     */
    private final transient Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }
}
