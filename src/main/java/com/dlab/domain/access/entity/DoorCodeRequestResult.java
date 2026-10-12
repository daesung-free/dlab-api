package com.dlab.domain.access.entity;

/**
 * 긴급 출입 요청 판정.
 *
 * <h2>★ 학생에게는 하나로 보이고 관리자에게는 갈린다</h2>
 * 학생 화면은 전부 「확인할 수 없습니다」다 — 어느 조건에 걸렸는지 알려주면 <b>번호를
 * 두드려 보는 사람에게 힌트</b>가 된다. 반면 <b>관리자 내역에서는 갈라야</b> 전화를 받은
 * 담당자가 원인을 알고 조치할 수 있다.
 */
public enum DoorCodeRequestResult {

    /** 제공했다 */
    PROVIDED("제공"),

    /**
     * 그 번호의 재원생이 없다.
     *
     * <p>⚠️ <b>연락처가 등록되지 않은 학생도 여기로 들어온다.</b> 번호로 찾는 구조라
     * 번호가 빈 학생은 애초에 검색에 걸리지 않아 「없음」과 구분되지 않는다 —
     * 화면 문구에 그 사실을 밝혀야 한다.
     */
    NOT_FOUND("일치하는 재원생 없음(연락처 미등록 포함)"),

    /**
     * 같은 번호를 쓰는 재원생이 둘 이상이다.
     *
     * <p>학생 연락처는 UNIQUE 가 아니다 — 형제가 같은 보호자 번호를 적어 둔 경우가 있다.
     * <b>어느 학생인지 정해지지 않으므로 제공하지 않는다.</b>
     */
    DUPLICATE_PHONE("같은 번호를 쓰는 학생이 둘 이상"),

    /** 찾았으나 재원생이 아니다(퇴원·제적). 번호를 받아 가면 안 된다 */
    NOT_ENROLLED("재원생이 아님"),

    /** 제공 시간(06:59~08:00) 밖이다 */
    OUTSIDE_WINDOW("제공 시간 밖"),

    /**
     * 지점에 등록된 번호가 없다.
     *
     * <p>★ <b>조치 대상이 학생이 아니라 지점 관리자다.</b> 학생은 정상인데 지점이 번호를
     * 넣어 두지 않은 것이라, {@link #NOT_FOUND} 와 섞이면 담당자가 학생을 붙잡고 묻는다.
     */
    NO_CODE_REGISTERED("지점에 등록된 번호 없음"),

    /**
     * 시도가 너무 많다.
     *
     * <p>★ <b>이것도 기록한다.</b> 막은 시도를 안 남기면 <b>공격이 일어난 사실 자체가
     * 보이지 않는다</b> — 막기만 하고 기록하지 않으면 아무도 모른다.
     */
    RATE_LIMITED("시도 횟수 초과");

    private final String label;

    DoorCodeRequestResult(String label) {
        this.label = label;
    }

    /** 관리자 화면에 그대로 쓰는 문구. 학생에게는 쓰지 않는다 */
    public String label() {
        return label;
    }

    /** 지점이 조치해야 하는 건가 — 화면이 색을 달리해 묶는다 */
    public boolean needsBranchAction() {
        return this == NO_CODE_REGISTERED;
    }
}
