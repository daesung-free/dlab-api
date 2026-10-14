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

    /**
     * 학생 한 명.
     *
     * <p>⚠️ <b>여러 명에게 보낼 때 쓰지 말 것</b> — 사람 수만큼 공지가 만들어져
     * 목록에 같은 글이 N개 뜨고, 수정도 집계도 쪼개진다. 그때는 {@link #SELECTED} 다.
     */
    INDIVIDUAL,

    /**
     * 고른 학생들.
     *
     * <h2>★ 대상자가 {@code notice_target} 에 있다</h2>
     * 「반 내에 해당하는 <b>학생들을</b> 개별 선택하여 발송」(요구사항 1-5)이 이것이다 —
     * 원문이 <b>복수</b>다.
     *
     * <p>공지 자체에는 {@code academy_id} 만 채워지고 {@code class_master_id}·
     * {@code enrollment_id} 는 빈다. 누가 받는지는 <b>대상자 테이블이 정한다.</b>
     */
    SELECTED
}
