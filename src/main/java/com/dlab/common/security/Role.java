package com.dlab.common.security;

/**
 * RBAC 5단계 역할.
 *
 * <p><b>이 enum은 "누구인가"만 정의한다. "무엇을 할 수 있는가"는 코드가 아니라
 * {@code permission} 테이블 데이터로 관리한다</b>(role_id × resource × action × academy_scope).
 * 권한 매트릭스가 아직 미수령이라(요구사항정의서가 "4시트 ①"을 소스로 인용하는데 실제로
 * 그 내용이 없다) 매트릭스를 코드에 박으면 수령 시 전부 갈아엎어야 한다.
 * {@code approval_item}을 데이터로 뺀 것과 같은 이유다.
 *
 * <p>DB의 {@code role.name}과 이름이 1:1로 대응한다.
 */
public enum Role {

    /** 본사 최고관리자. 전 지점 조회·수정 가능. */
    SUPER_ADMIN,

    /** 지점 관리자. 소속 지점 범위 안에서 전권. */
    BRANCH_ADMIN,

    /** 담당선생님(사감). 담당 반 학생에 대한 승인·상담·출결 처리. */
    TEACHER,

    /** 행정선생님. 전체공지 작성 등 행정 업무. */
    STAFF,

    /** 조회 전용. */
    READONLY;

    /**
     * 서열 — 작을수록 높다. <b>자기보다 높은 역할은 부여할 수 없다.</b>
     *
     * <p>{@code TEACHER}와 {@code STAFF}는 같은 층이다 — 담당/행정은 상하가 아니라
     * 소속이 다른 것이고, 그 구분은 {@code teacher}/{@code employee} 테이블이 한다.
     *
     * <p>⚠️ <b>권한 매트릭스(permission 테이블)를 대신하는 값이 아니다.</b> 여기서 막는 것은
     * "누가 누구를 만들 수 있는가" 하나뿐이고, "무엇을 할 수 있는가"는 여전히 데이터다.
     */
    public int rank() {
        return switch (this) {
            case SUPER_ADMIN -> 0;
            case BRANCH_ADMIN -> 1;
            case TEACHER, STAFF -> 2;
            case READONLY -> 3;
        };
    }

    /** 화면에 그대로 쓰는 표기. 하드코딩하면 화면마다 갈린다. */
    public String displayName() {
        return switch (this) {
            case SUPER_ADMIN -> "본사 최고관리자";
            case BRANCH_ADMIN -> "지점 관리자";
            case TEACHER -> "담당선생님(사감)";
            case STAFF -> "행정선생님";
            case READONLY -> "조회 전용";
        };
    }

    public String description() {
        return switch (this) {
            case SUPER_ADMIN -> "전 지점 조회·수정. 지점이 만든 계정을 승인한다.";
            case BRANCH_ADMIN -> "소속 지점 범위 안에서 전권.";
            case TEACHER -> "담당 반 학생의 승인·상담·출결 처리. 반 공지를 쓴다.";
            case STAFF -> "학생 가입 승인 등 행정 업무. 전체 공지를 쓴다.";
            case READONLY -> "조회만 가능하다.";
        };
    }

    /** Spring Security 권한 문자열. {@code hasRole()}이 ROLE_ 접두사를 요구한다. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Role from(String name) {
        return valueOf(name);
    }
}
