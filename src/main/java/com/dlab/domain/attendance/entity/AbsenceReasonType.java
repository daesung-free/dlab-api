package com.dlab.domain.attendance.entity;

public enum AbsenceReasonType {
    ABSENCE,
    LATE,
    EARLY_LEAVE,
    OUTING,

    /**
     * ★★ <b>더프리미엄 모의고사 응시로 인한 조퇴</b> (디랩 1029 4-4).
     *
     * <p>디랩 질문: <b>「사유 조퇴와 구분하여 신청이 가능할까요?」</b> → 구분해서 만들었다.
     *
     * <h2>★ 왜 일반 조퇴와 합치지 않는가</h2>
     * ⚠️ 합치면 <b>전일(-1)·당일(-2)로 점수를 가를 수 없다.</b> 시트가 그 둘을
     * 다른 항목으로 적는다(#13·#14).
     *
     * <p>★ <b>응시일에만 신청할 수 있다</b>({@code duf_exam_date}) — 아닌 날에
     * 신청하면 <b>일반 사유 조퇴로 안내</b>한다.
     *
     * <p>★ 벌점 조건값은 <b>{@code DUF_PREV_DAY} · {@code DUF_SAME_DAY}</b> 로
     * 갈린다 — ⚠️ 이 enum 이름이 아니다. <b>같은 신청 종류인데 점수가 둘</b>이라서다.
     */
    DUF_EARLY_LEAVE
}
