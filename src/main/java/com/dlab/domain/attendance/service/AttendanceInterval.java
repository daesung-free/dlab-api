package com.dlab.domain.attendance.service;

import com.dlab.domain.attendance.entity.AttendanceEventType;
import java.time.Instant;

/**
 * 하루 안의 외출·조퇴 한 건 (2026-10-13 디랩 피드백 5-5).
 *
 * <p>⚠️ 앱 응답의 {@code AttendanceResponse.AttendanceEvent} 와 <b>다른 것</b>이다 —
 * 그쪽은 태깅 <b>한 건</b>을 그대로 내리고, 이것은 외출과 복귀를 <b>짝지은 구간</b>이다.
 * 이름이 비슷해 섞기 쉬우니 주의할 것.
 *
 * <p>요구는 <b>「외출·조퇴를 등원과 하원 사이에, 사유와 함께」</b>다. 출결 행에는
 * 등원·하원 시각만 있어서 그 사이에 무엇이 있었는지 보이지 않았다.
 *
 * <h2>★ 한 칸으로는 안 된다</h2>
 * 하루에 외출·복귀가 <b>여러 번</b> 있을 수 있다. 그래서 행에 <b>구간 목록</b>으로 싣는다.
 *
 * <h2>★★ 「안 돌아왔다」와 「복귀를 안 찍었다」는 다른 사건이다</h2>
 * 둘 다 복귀 기록이 없지만 <b>담당자가 할 일이 다르다</b> — 전자는 지금 밖에 있으니 찾아야
 * 하고, 후자는 기록을 고쳐야 한다. {@code returnedAt} 이 {@code null} 인 것만으로는
 * 구분되지 않아 {@link ReturnState} 로 가른다(좌석 이탈의 {@code NO_RETURN_RECORD} 와 같은 판단).
 *
 * @param at         발생 시각. <b>ISO 인스턴트</b>다 — {@code "13:20"} 같은 시각 문자열로
 *                   두면 같은 행의 등원·하원과 형식이 섞이고, <b>자정을 넘긴 외출</b>
 *                   (23:50 외출 → 00:10 복귀)을 표현할 수 없다
 * @param returnedAt 복귀 시각. 돌아오지 않았거나 조퇴면 {@code null}
 * @param excused    사유 승인된 외출인가 — {@code N}(사유외출)과 {@code D}(그냥 외출)를 가른다
 * @param reason     사유 문구. 사유신청이 있을 때만 있다
 */
public record AttendanceInterval(
        AttendanceEventType type,
        Instant at,
        Instant returnedAt,
        ReturnState returnState,
        boolean excused,
        String reason) {

    /** 복귀 상태. <b>{@code returnedAt == null} 하나로는 구분되지 않는 것</b>을 가른다. */
    public enum ReturnState {

        /** 돌아왔다 */
        RETURNED,

        /** <b>아직 밖에 있다</b> — 오늘이고 복귀 기록이 없다. 찾아야 할 상태다 */
        PENDING,

        /**
         * <b>복귀 기록이 없다</b> — 그날이 지났는데 복귀가 없다.
         *
         * <p>학생이 안 돌아온 것이 아니라 <b>태깅을 안 찍은 것</b>으로 본다. 기록을 고쳐야
         * 순공시간이 맞는다 — 그대로 두면 하원까지 밖에 있던 것으로 계산된다.
         */
        NO_RETURN_RECORD,

        /** 복귀를 기대하지 않는다 — <b>조퇴</b>다. 「미복귀」로 표시하면 오해를 만든다 */
        NOT_EXPECTED
    }
}
