package com.dlab.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 도메인 오류 코드. 새 오류가 필요하면 여기 추가한다 (CLAUDE.md §7).
 */
public enum ErrorCode {

    // 공통
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),
    ACCOUNT_NOT_ACTIVE(HttpStatus.FORBIDDEN, "사용할 수 없는 계정입니다."),
    SIGNUP_PENDING(HttpStatus.FORBIDDEN, "가입 승인 대기 중입니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    // 로그인 보안 (A-1 · F-4.12-1)
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN,
            "로그인 실패가 반복되어 계정이 잠겼습니다. 관리자에게 문의해 주세요."),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN,
            "임시 비밀번호 상태입니다. 비밀번호를 변경한 뒤 이용해 주세요."),
    PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "비밀번호 정책에 맞지 않습니다."),
    PASSWORD_SAME_AS_BEFORE(HttpStatus.BAD_REQUEST, "이전과 다른 비밀번호를 입력해 주세요."),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "계정을 찾을 수 없습니다."),
    // 휴대폰 본인인증 (A-2)
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증번호가 만료되었습니다. 다시 요청해 주세요."),
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않습니다."),
    VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.BAD_REQUEST,
            "인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요."),
    PHONE_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "휴대폰 인증이 완료되지 않았습니다."),
    // 학부모 가입·자녀 연결 (A-2)
    STUDENT_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "학생 고유ID를 찾을 수 없습니다."),
    GUARDIAN_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 학부모가 연결된 학생입니다."),
    CHILD_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결된 자녀입니다."),
    PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 휴대폰 번호입니다."),
    NOT_MY_CHILD(HttpStatus.FORBIDDEN, "본인의 자녀가 아닙니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 지점 / 조직
    ACADEMY_NOT_FOUND(HttpStatus.NOT_FOUND, "지점을 찾을 수 없습니다."),
    CLASS_NOT_FOUND(HttpStatus.NOT_FOUND, "반을 찾을 수 없습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "좌석을 찾을 수 없습니다."),
    SEAT_ALREADY_OCCUPIED(HttpStatus.CONFLICT, "이미 배정된 좌석입니다."),
    SEAT_NOT_ASSIGNED(HttpStatus.NOT_FOUND, "배정된 좌석이 없습니다."),
    MASTER_NOT_FOUND(HttpStatus.NOT_FOUND, "기초 데이터를 찾을 수 없습니다."),
    LOCKER_ALREADY_OCCUPIED(HttpStatus.CONFLICT, "이미 배정된 사물함입니다."),
    OTHER_BRANCH_ACCESS_DENIED(HttpStatus.FORBIDDEN, "다른 지점의 데이터에 접근할 수 없습니다."),
    SNAPSHOT_TARGET_NOT_EMPTY(HttpStatus.CONFLICT, "복사 대상 연도에 이미 기초 데이터가 있습니다."),

    // 사용자
    STUDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학생을 찾을 수 없습니다."),
    PARENT_NOT_FOUND(HttpStatus.NOT_FOUND, "학부모를 찾을 수 없습니다."),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "직원을 찾을 수 없습니다."),
    ENROLLMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "등록 정보를 찾을 수 없습니다."),
    INVALID_ENROLLMENT_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "허용되지 않는 재원 상태 변경입니다."),
    DUPLICATE_SIGNUP(HttpStatus.CONFLICT, "이미 가입 신청된 번호입니다."),
    STUDENT_NOT_APPROVED(HttpStatus.FORBIDDEN, "가입 승인 대기 중입니다."),

    // 승인 (사유신청·정기일정·방화벽 공용)
    APPROVAL_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "승인 정책을 찾을 수 없습니다."),
    APPROVAL_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "승인 요청을 찾을 수 없습니다."),
    APPROVAL_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 신청입니다."),
    NOT_AN_APPROVER(HttpStatus.FORBIDDEN, "이 신청의 승인자가 아닙니다."),

    // 알림
    NOTIFICATION_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 템플릿을 찾을 수 없습니다."),
    NOTIFICATION_VARIABLE_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "알림 템플릿 변수가 누락되었습니다."),

    // 공휴일
    HOLIDAY_NOT_FOUND(HttpStatus.NOT_FOUND, "공휴일을 찾을 수 없습니다."),
    HOLIDAY_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 날짜입니다."),
    /** 법정공휴일은 전 지점에 적용되므로 본사만 등록할 수 있다. */
    NATIONWIDE_HOLIDAY_FORBIDDEN(HttpStatus.FORBIDDEN, "전 지점 공휴일은 본사만 등록할 수 있습니다.");

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
