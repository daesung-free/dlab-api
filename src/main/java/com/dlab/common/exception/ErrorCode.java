package com.dlab.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인 오류 코드. 새 오류가 필요하면 여기 추가한다 (CLAUDE.md §7).
 */
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 지점 / 조직
    BRANCH_NOT_FOUND(HttpStatus.NOT_FOUND, "지점을 찾을 수 없습니다."),
    CLASS_NOT_FOUND(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."),
    OTHER_BRANCH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "다른 지점의 데이터에 접근할 수 없습니다."),

    // 사용자
    STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다."),
    PARENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학부모를 찾을 수 없습니다."),
    STAFF_NOT_FOUND(HttpStatus.NOT_FOUND, "직원을 찾을 수 없습니다."),
    STUDENT_NOT_APPROVED(HttpStatus.FORBIDDEN, "가입 승인 대기 중입니다."),

    // 방화벽 해제 승인
    FIREWALL_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "방화벽 해제 신청을 찾을 수 없습니다."),
    APPROVAL_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 신청입니다."),
    NOT_AN_APPROVER(HttpStatus.FORBIDDEN, "이 신청의 승인자가 아닙니다."),

    // 알림
    NOTIFICATION_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 템플릿을 찾을 수 없습니다."),
    NOTIFICATION_VARIABLE_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "알림 템플릿 변수가 누락되었습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
