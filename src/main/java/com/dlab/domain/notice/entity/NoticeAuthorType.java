package com.dlab.domain.notice.entity;

/**
 * 공지 작성자 소속.
 *
 * <p>행정선생님은 {@code employee}, 담당선생님(사감)은 {@code teacher}로 <b>테이블이 다르다</b>
 * (지점마다 강사 조직과 행정 조직이 분리 운영돼 겸직이 없어서 합치지 않았다).
 * 그래서 작성자를 하나의 FK로 못 받고 다형 참조가 된다.
 *
 * <p>{@code created_by}로는 대신할 수 없다 — 그건 계정 id라
 * <b>"어느 조직 소속이 썼는가"</b>에 답하지 못한다.
 */
public enum NoticeAuthorType {

    /** 행정선생님. */
    EMPLOYEE,

    /** 담당선생님(사감). */
    TEACHER
}
