package com.dlab.domain.approval.entity;

/**
 * 승인 대상 유형. 사유신청·정기일정·방화벽이 하나의 라우팅 엔진을 공유한다
 * — 각자 승인 컬럼을 갖게 하면 같은 로직을 세 번 짜게 된다.
 */
public enum RequestType {

    FIREWALL_UNLOCK("와이파이 해제"),
    ABSENCE_REASON("사유 신청"),
    REGULAR_SCHEDULE("정기일정");

    /**
     * 알림 문구에 들어갈 이름.
     *
     * <h2>★ 알림에 「무엇을 승인하는지」가 없으면 학부모가 모른다</h2>
     * 지금 승인 요청 알림은 <b>「승인 요청이 왔습니다」</b>까지만 말한다 — 학부모는
     * 와이파이인지 외출인지 모른 채 승인하게 된다. 디랩이 <b>넣는 쪽(B안)</b>으로 정했다.
     *
     * <h2>⚠️ 이름을 enum 상수로 만들지 않는다</h2>
     * {@code FIREWALL_UNLOCK} 을 그대로 쓰면 학부모 휴대폰에 영문 상수가 찍힌다.
     * 반대로 한글을 상수명으로 쓰면 DB 에 저장된 값이 한글이 되어 <b>문구를 다듬는 순간
     * 과거 데이터가 깨진다.</b> 저장값과 표시값을 나눠 둔다.
     */
    private final String displayName;

    RequestType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
