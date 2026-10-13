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
    NONE("기본", Group.OK),
    SLEEP("졸음", Group.WARN),
    OUTING("외출", Group.OUTSIDE),
    EARLY_LEAVE("조퇴", Group.OUTSIDE),
    CLASSROOM("강의실", Group.INSIDE),
    TOILET("화장실", Group.BRIEF),
    COMMON("공용공간", Group.BRIEF),
    SUBJECT_ROOM("교과실", Group.INSIDE),
    QNA("질답", Group.INSIDE),
    CONSULT("상담", Group.INSIDE);

    private final String label;
    private final Group group;

    PatrolMark(String label, Group group) {
        this.label = label;
        this.group = group;
    }

    /**
     * 묶음 — <b>사감이 그 자리에서 할 일</b> 기준이다.
     *
     * <p>★ <b>화면이 코드로 묶지 않게 서버가 내린다.</b> 화면에서 묶으면 상태가 늘 때마다
     * 화면을 고쳐야 하고, 고치는 것을 잊으면 <b>새 상태가 아무 묶음에도 안 들어가</b>
     * 조용히 빠진다(문구를 서버가 내리는 것과 같은 이유다).
     */
    public Group group() {
        return group;
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

    /** 사감이 할 일 기준 묶음. 화면이 색을 가르는 데 쓴다 */
    public enum Group {
        /** 정상 — 자리에서 공부 중 */
        OK,
        /** <b>깨워야 한다</b> — 자리에 있는데 조치가 필요하다 */
        WARN,
        /** 원내에 있고 사유가 분명하다 — 강의실·교과실·질답·상담 */
        INSIDE,
        /** 잠시 비움 — 화장실·공용공간. 곧 돌아온다 */
        BRIEF,
        /** 원 밖 — 외출·조퇴 */
        OUTSIDE
    }
}
