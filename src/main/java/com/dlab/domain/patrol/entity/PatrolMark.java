package com.dlab.domain.patrol.entity;

/**
 * 순찰 상태 (2026-10-13 디랩 피드백 1-3 · 요구사항 29쪽 10종).
 *
 * <h2>★ enum 으로 고정한다</h2>
 * 좌석 이탈 사유처럼 <b>지점별 자유 문자열로 두면 집계가 안 된다</b> — 「졸음」과 「졸음.」이
 * 다른 값이 되고, 「2회 연속 졸음」 같은 규칙을 걸 수가 없다.
 */
public enum PatrolMark {

    /** 기본 — 자리에서 공부 중 */
    NONE("기본"),
    SLEEP("졸음"),
    OUTING("외출"),
    EARLY_LEAVE("조퇴"),
    CLASSROOM("강의실"),
    TOILET("화장실"),
    COMMON("공용공간"),
    SUBJECT_ROOM("교과실"),
    QNA("질답"),
    CONSULT("상담");

    private final String label;

    PatrolMark(String label) {
        this.label = label;
    }

    /** 화면 표기. 서버가 내려 화면이 문구를 중복 정의하지 않게 한다 */
    public String label() {
        return label;
    }

    /**
     * 자리를 비운 상태인가.
     *
     * <p>★ <b>「없다」와 「졸고 있다」는 다르다.</b> 순공시간·상벌점 규칙이 나중에 붙을 때
     * 이 구분이 필요하다 — 졸음은 자리에 있는 것이다.
     */
    public boolean away() {
        return this != NONE && this != SLEEP;
    }
}
