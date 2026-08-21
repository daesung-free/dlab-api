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
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 경로를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 방식입니다."),
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

    // 사유 신청 (F-4.1-6 · 앱 제출)
    ABSENCE_REASON_NOT_FOUND(HttpStatus.NOT_FOUND, "사유 신청을 찾을 수 없습니다."),
    ABSENCE_REASON_DUPLICATED(HttpStatus.CONFLICT, "같은 날짜에 이미 신청한 유형입니다."),
    /** 승인·반려된 건은 이력이라 지우지 않는다. 되돌릴 일이면 관리자가 정정한다. */
    ABSENCE_REASON_NOT_CANCELABLE(HttpStatus.CONFLICT, "이미 처리된 신청은 취소할 수 없습니다."),

    // 앱 설정 · 약관 (A-21 · F-4.12-3)
    TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "약관을 찾을 수 없습니다."),
    TERMS_VERSION_DUPLICATED(HttpStatus.CONFLICT,
            "같은 버전의 약관이 이미 있습니다. 문구를 고치려면 버전을 올려주세요."),
    REQUIRED_TERMS_CANNOT_BE_REVOKED(HttpStatus.BAD_REQUEST, "필수 약관은 철회할 수 없습니다."),
    REQUIRED_NOTIFICATION_CANNOT_BE_DISABLED(HttpStatus.BAD_REQUEST,
            "이 알림은 수신 거부할 수 없습니다."),

    // 특강 (F-4.7 · F-4.10-4 · A-15)
    LECTURE_NOT_FOUND(HttpStatus.NOT_FOUND, "특강을 찾을 수 없습니다."),
    LECTURE_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "특강 회차를 찾을 수 없습니다."),
    LECTURE_APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "특강 신청을 찾을 수 없습니다."),
    LECTURE_NOT_ACCEPTING(HttpStatus.BAD_REQUEST, "지금은 신청할 수 없는 특강입니다."),
    LECTURE_ALREADY_APPLIED(HttpStatus.CONFLICT, "이미 신청한 특강입니다."),
    LECTURE_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 신청입니다."),
    LECTURE_CAPACITY_BELOW_CONFIRMED(HttpStatus.BAD_REQUEST, "확정 인원보다 적은 정원으로 줄일 수 없습니다."),

    // 질의응답 대면 (F-4.11-7 · A-13)
    QNA_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 타임을 찾을 수 없습니다."),
    QNA_RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    QNA_SLOT_CLOSED(HttpStatus.BAD_REQUEST, "예약이 마감된 시간입니다."),
    QNA_SLOT_FULL(HttpStatus.CONFLICT, "정원이 찼습니다."),
    QNA_SLOT_PAST(HttpStatus.BAD_REQUEST, "이미 지난 시간입니다."),
    QNA_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약한 시간입니다."),
    QNA_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    // 상담 예약 (F-4.11-4, 0803 답변서)
    CONSULT_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 일정을 찾을 수 없습니다."),
    CONSULT_RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 예약을 찾을 수 없습니다."),
    CONSULT_SLOT_NOT_OPEN(HttpStatus.BAD_REQUEST, "아직 예약을 받지 않는 일정입니다."),
    CONSULT_SLOT_FULL(HttpStatus.CONFLICT, "정원이 찼습니다."),
    CONSULT_SLOT_PAST(HttpStatus.BAD_REQUEST, "이미 지난 시간입니다."),
    CONSULT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약한 시간입니다."),
    CONSULT_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    /** 반 배정이 없거나 그 반에 담임이 지정되지 않았다 — 상담을 잡을 대상이 없다. */
    CONSULT_NO_HOMEROOM(HttpStatus.BAD_REQUEST, "담당선생님이 배정되지 않아 상담을 예약할 수 없습니다."),
    CONSULT_NOT_MY_HOMEROOM(HttpStatus.FORBIDDEN, "담당선생님의 일정만 예약할 수 있습니다."),
    // 데일리 루틴 (F-4.11-1 · A-11)
    ROUTINE_NOT_FOUND(HttpStatus.NOT_FOUND, "루틴을 찾을 수 없습니다."),
    ROUTINE_TARGET_MONTH_NOT_EMPTY(HttpStatus.CONFLICT,
            "복사 대상 월에 이미 루틴이 있습니다. 덮어쓰지 않습니다."),
    ROUTINE_SOURCE_MONTH_EMPTY(HttpStatus.NOT_FOUND, "전월에 복사할 루틴이 없습니다."),
    ROUTINE_SCORE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "점수가 만점 범위를 벗어났습니다."),

    // 알림
    NOTIFICATION_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "알림 템플릿을 찾을 수 없습니다."),
    NOTIFICATION_VARIABLE_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "알림 템플릿 변수가 누락되었습니다."),
    NOTIFICATION_TEMPLATE_DUPLICATED(HttpStatus.CONFLICT, "이 이벤트의 템플릿이 이미 있습니다."),
    NOTIFICATION_TEMPLATE_CONTENT_EMPTY(HttpStatus.BAD_REQUEST,
            "문구가 비어 있어 확정할 수 없습니다."),
    NOTIFICATION_REVIEW_NOT_APPLICABLE(HttpStatus.BAD_REQUEST,
            "카카오 알림톡 템플릿만 심사 대상입니다."),

    // 급식 (F-4.5 · A-9)
    MEAL_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "급식 주문을 찾을 수 없습니다."),
    MEAL_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "급식 신청 내역을 찾을 수 없습니다."),
    /** 기간 밖에는 신청 화면이 열리지 않는다. 미등록도 닫힘이다. */
    MEAL_WINDOW_CLOSED(HttpStatus.CONFLICT, "지금은 급식 신청 기간이 아닙니다."),
    /** 주말·공휴일·중단일. */
    MEAL_DATE_NOT_AVAILABLE(HttpStatus.CONFLICT, "급식을 신청할 수 없는 날짜입니다."),
    MEAL_DEADLINE_PASSED(HttpStatus.CONFLICT, "신청·취소 마감이 지났습니다."),
    MEAL_ALREADY_APPLIED(HttpStatus.CONFLICT, "이미 신청한 끼니입니다."),
    MEAL_CLOSURE_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 급식 중단일입니다."),
    /** 앱이 이 오류를 받으면 급식업체 제3자 제공 동의 화면을 띄운다. */
    MEAL_THIRD_PARTY_CONSENT_REQUIRED(HttpStatus.BAD_REQUEST,
            "급식업체 개인정보 제3자 제공에 동의해야 신청할 수 있습니다."),
    // 급식업체·단가 (0820 규정)
    MEAL_VENDOR_NOT_FOUND(HttpStatus.NOT_FOUND, "급식업체를 찾을 수 없습니다."),
    /** 같은 업체가 두 벌이면 연락처를 고칠 때 어느 쪽이 진짜인지 알 수 없다. */
    MEAL_VENDOR_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 급식업체입니다."),
    MEAL_POLICY_NOT_FOUND(HttpStatus.NOT_FOUND, "지점 급식 설정이 없습니다."),

    // 교습비 가격 (F-4.10-5 · 0820 규정)
    TUITION_PRICE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 조건의 교습비가 등록되지 않았습니다."),
    /** 지점 관리자가 공통 가격을 고치면 나머지 지점 청구가 같이 바뀐다. */
    TUITION_PRICE_SCOPE_FORBIDDEN(HttpStatus.FORBIDDEN, "전 지점 공통 교습비는 본사만 다룰 수 있습니다."),
    /**
     * ★ 달력 일수로 대신 계산하지 않는다. 표가 2월 27일·9월 29일이라
     * 달력(28·30)으로 떨어뜨리면 조용히 틀린 금액이 청구된다.
     */
    TEACHING_DAYS_NOT_REGISTERED(HttpStatus.NOT_FOUND, "그 달의 교습일수가 등록되지 않았습니다."),

    // 공지 (F-4.11-3)
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공지를 찾을 수 없습니다."),
    /** 범위마다 쓸 수 있는 사람이 다르다 — 전체는 본사, 지점은 지점관리자, 반은 담임이다. */
    NOTICE_SCOPE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 범위의 공지를 작성할 권한이 없습니다."),

    // 설문 (F-4.11-3 · A-14)
    SURVEY_NOT_FOUND(HttpStatus.NOT_FOUND, "설문을 찾을 수 없습니다."),
    SURVEY_QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "설문 문항을 찾을 수 없습니다."),
    SURVEY_OPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "선택지를 찾을 수 없습니다."),
    /** 기간 판정은 서버만 한다 — 앱이 시각을 비교하면 기기 시계에 좌우된다. */
    SURVEY_CLOSED(HttpStatus.CONFLICT, "지금은 설문 응답 기간이 아닙니다."),
    SURVEY_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 응답한 설문입니다."),
    SURVEY_REQUIRED_ANSWER_MISSING(HttpStatus.BAD_REQUEST, "필수 문항에 답하지 않았습니다."),
    SURVEY_ANSWER_INVALID(HttpStatus.BAD_REQUEST, "문항 유형에 맞지 않는 답입니다."),
    SURVEY_QUESTION_EMPTY(HttpStatus.BAD_REQUEST, "문항이 없는 설문은 만들 수 없습니다."),
    SURVEY_OPTION_EMPTY(HttpStatus.BAD_REQUEST, "선택형 문항에는 선택지가 필요합니다."),
    /** 범위마다 낼 수 있는 사람이 다르다 — 전 지점은 본사, 반은 담임이다. */
    SURVEY_SCOPE_FORBIDDEN(HttpStatus.FORBIDDEN, "이 범위의 설문을 낼 권한이 없습니다."),

    // 공휴일
    HOLIDAY_NOT_FOUND(HttpStatus.NOT_FOUND, "공휴일을 찾을 수 없습니다."),
    HOLIDAY_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 날짜입니다."),
    /** 법정공휴일은 전 지점에 적용되므로 본사만 등록할 수 있다. */
    NATIONWIDE_HOLIDAY_FORBIDDEN(HttpStatus.FORBIDDEN, "전 지점 공휴일은 본사만 등록할 수 있습니다."),

    // 교시
    PERIOD_NOT_FOUND(HttpStatus.NOT_FOUND, "교시를 찾을 수 없습니다."),
    PERIOD_NO_DUPLICATED(HttpStatus.CONFLICT, "같은 요일 구분에 이미 있는 교시 번호입니다."),
    /** 겹치면 한 시각이 두 교시에 걸려 출결 판정·순공시간이 흔들린다. */
    PERIOD_TIME_OVERLAPPED(HttpStatus.CONFLICT, "다른 교시와 시간이 겹칩니다."),
    /** 교시가 하나도 없는 날은 "운영일 아님"이 돼 태깅이 전부 거부된다. */
    PERIOD_LAST_ONE(HttpStatus.CONFLICT,
            "마지막 교시는 삭제할 수 없습니다. 새 교시를 먼저 등록하세요.");

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
