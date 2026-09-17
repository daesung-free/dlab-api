package com.dlab.domain.penalty;

import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.service.DailyAttendanceConfirmService;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.entity.TriggerCondition;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트리거 조건 목록.
 *
 * <p>★ <b>이 테스트가 지키는 것은 "목록과 호출부가 같은 문자열을 쓴다" 하나다.</b>
 * 둘이 어긋나면 화면에서 고른 값이 엔진에 걸리지 않는데, 증상은 "규칙은 있는데 점수가
 * 안 붙는다" 뿐이라 원인을 찾기 어렵다.
 */
class TriggerConditionTest {

    @Test
    @DisplayName("★ 출결 조건은 태깅 코드 전부 + 결석이다 — 결석만 태깅이 아니라 배치가 넣는다")
    void attendanceConditionsMatchEngineInput() {
        List<String> values = TriggerCondition.optionsOf(PenaltyTriggerType.ATTENDANCE)
                .stream().map(TriggerCondition.Option::value).toList();

        // 키오스크가 태깅할 때 엔진에 넘기는 값
        assertThat(values).containsAll(
                Arrays.stream(AttendanceEventType.values())
                        .map(AttendanceEventType::getCode).toList());

        // 확정 배치가 넘기는 값. 코드표에 없어서 빠뜨리기 쉽다
        assertThat(values).contains(DailyAttendanceConfirmService.ABSENT_CONDITION);
    }

    @Test
    @DisplayName("루틴 조건은 결과 상태 그대로다 — 공개 시점에 status.name() 이 넘어간다")
    void routineConditionsMatchStatusNames() {
        List<String> values = TriggerCondition.optionsOf(PenaltyTriggerType.DAILY_ROUTINE)
                .stream().map(TriggerCondition.Option::value).toList();

        assertThat(values).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(RoutineResultStatus.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("정기일정은 조건이 하나뿐이다")
    void scheduleHasSingleCondition() {
        assertThat(TriggerCondition.optionsOf(PenaltyTriggerType.REGULAR_SCHEDULE))
                .extracting(TriggerCondition.Option::value)
                .containsExactly("NOT_RECOGNIZED");
    }

    @Test
    @DisplayName("★ 목록에 없는 값은 거절한다 — 전에는 ZZZZ 도 저장돼 영영 안 걸리는 규칙이 됐다")
    void rejectsUnknownCondition() {
        assertThat(TriggerCondition.isValid(PenaltyTriggerType.ATTENDANCE, "ZZZZ")).isFalse();
        assertThat(TriggerCondition.isValid(PenaltyTriggerType.ATTENDANCE, "A")).isTrue();
        // 트리거가 다르면 같은 값이라도 못 쓴다 — 루틴에 태깅 코드를 넣어도 걸리지 않는다
        assertThat(TriggerCondition.isValid(PenaltyTriggerType.DAILY_ROUTINE, "A")).isFalse();
    }

    @Test
    @DisplayName("대소문자는 가리지 않는다 — 엔진 비교도 그렇다")
    void caseInsensitive() {
        assertThat(TriggerCondition.isValid(PenaltyTriggerType.ATTENDANCE, "absent")).isTrue();
        assertThat(TriggerCondition.isValid(PenaltyTriggerType.ATTENDANCE, " a ")).isTrue();
    }
}
