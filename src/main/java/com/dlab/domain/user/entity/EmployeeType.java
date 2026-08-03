package com.dlab.domain.user.entity;

/**
 * 직원 구분. 공지 작성 권한이 이 값에 따라 갈린다
 * (전체공지=ADMIN, 반공지=HOMEROOM).
 *
 * <p>요구사항정의서는 RBAC를 5단계 권한등급으로 두는데 이 구분은 직무 축이라 서로를
 * 포함하지 못한다 — 축 정리는 §4 블로커다. 확정 전까지 role/permission 테이블은 만들지 않는다.
 */
public enum EmployeeType {
    /** 담당선생님(사감) — 반공지 작성, 승인 에스컬레이션 대상 */
    HOMEROOM,
    /** 행정선생님 — 전체공지 작성 */
    ADMIN,
    /** 상위 관리자 — 전 지점 조회 */
    SUPER
}
