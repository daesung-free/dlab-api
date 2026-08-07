package com.dlab.domain.notice.entity;

/**
 * 공지 발송 범위 (F-4.11-3).
 *
 * <p><b>범위마다 쓸 수 있는 사람이 다르다.</b> 하나로 합치고 권한만 나누면
 * "지점관리자가 실수로 전 지점에 공지"가 가능해진다.
 */
public enum NoticeScope {

    /** 전 지점. <b>본사(전 지점 권한자)만</b> 쓴다. */
    ALL,

    /** 한 지점 전체. 지점관리자 이상. */
    BRANCH,

    /** 한 반. <b>그 반 담임</b> 또는 지점관리자 이상. */
    CLASS,

    /** 학생 한 명. */
    INDIVIDUAL
}
