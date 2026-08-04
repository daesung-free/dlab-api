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

    /** Spring Security 권한 문자열. {@code hasRole()}이 ROLE_ 접두사를 요구한다. */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Role from(String name) {
        return valueOf(name);
    }
}
