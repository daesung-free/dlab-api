package com.dlab.domain.penalty.entity;

import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 트리거별로 넣을 수 있는 조건값.
 *
 * <h2>왜 필요한가</h2>
 * {@code trigger_condition}은 자유 문자열이라 <b>무엇을 넣어야 하는지 알 방법이 없었다.</b>
 * 스펙에는 "출결이면 att_gn" 한 줄뿐이고 코드표가 없었으며, 서버도 검증하지 않아
 * {@code "ZZZZ"} 같은 값이 그대로 저장됐다. 그러면 <b>영영 걸리지 않는 규칙</b>이 조용히
 * 쌓이고, 화면에는 "규칙을 만들었는데 점수가 안 붙는다"로만 보인다 — 단서가 없다.
 *
 * <h2>값은 어디서 오나</h2>
 * 엔진이 비교하는 것은 <b>호출부가 넘기는 문자열</b>이라, 그 문자열을 만드는 쪽이 곧 정답이다.
 * <ul>
 *   <li>{@link PenaltyTriggerType#ATTENDANCE} — 키오스크 태깅 코드
 *       ({@link AttendanceEventType#getCode()}, {@code S·T·A·D·N·C·R})
 *       <b>+ {@code ABSENT}</b>. ★ 결석은 태깅 이벤트가 아니다 — 아무도 찍지 않은 것을
 *       일자 확정 배치가 판정해 넣는다. 그래서 코드표에 없는데도 유효하다.
 *       시드에 {@code "A"}와 {@code "ABSENT"}가 섞여 보이는 이유이고, <b>둘 다 맞다.</b>
 *       <b>+ {@link #LATE_RETURN}</b> 도 같은 부류다 — 단말은 「복귀」만 보내고 예정
 *       시각과 비교하는 것은 서버다.</li>
 *   <li>{@link PenaltyTriggerType#DAILY_ROUTINE} — {@link RoutineResultStatus} 이름</li>
 *   <li>{@link PenaltyTriggerType#REGULAR_SCHEDULE} — {@code NOT_RECOGNIZED} 하나뿐</li>
 * </ul>
 *
 * <p>⚠️ <b>호출부의 문자열을 바꾸면 여기도 같이 바꿔야 한다.</b> 한쪽만 바뀌면 이미 등록된
 * 규칙이 조용히 안 걸린다.
 */
public final class TriggerCondition {

    /** 결석. 태깅이 아니라 일자 확정 배치가 넣는 값이다. */
    public static final String ABSENT = "ABSENT";

    /**
     * 지각 복귀 — <b>사유 외출의 복귀 예정 시각보다 늦게</b> 복귀했다 (디랩 1021 피드백 3-2).
     *
     * <p>디랩 원문: <i>"학생이 정해진 복귀시간보다 늦게 복귀하였을때, <b>지각복귀 벌점이
     * 2점</b>입니다."</i>
     *
     * <h2>★ 복귀 태깅 코드({@code R})와 따로 둔다</h2>
     * {@code R} 로 규칙을 만들면 <b>제시간에 복귀한 학생도 전부 걸린다.</b> 복귀는 정상
     * 행동이고 벌점은 <b>늦은 복귀에만</b> 붙는다.
     *
     * <h2>★ 결석({@link #ABSENT})과 같은 부류다</h2>
     * 태깅 코드가 아니라 <b>서버가 판정해 넣는 값</b>이다 — 단말은 「복귀」만 보내고,
     * 예정 시각과 비교하는 것은 우리다.
     *
     * <p>⚠️ <b>좌석 장시간 미복귀와 다른 것이다.</b> 저쪽은 자리를 비운 것이고 이쪽은
     * 건물 밖 외출이다(1020 좌석 문의의 미복귀 벌점과 섞지 말 것).
     */
    public static final String LATE_RETURN = "LATE_RETURN";

    /** 정기일정 미인정. 이 트리거의 유일한 조건값이다. */
    public static final String NOT_RECOGNIZED = "NOT_RECOGNIZED";

    private TriggerCondition() {
    }

    /**
     * 화면 드롭다운에 쓸 목록.
     *
     * @return 조건값과 사람이 읽는 이름
     */
    public static List<Option> optionsOf(PenaltyTriggerType triggerType) {
        return switch (triggerType) {
            case ATTENDANCE -> {
                List<Option> options = new java.util.ArrayList<>(
                        Arrays.stream(AttendanceEventType.values())
                                .map(e -> new Option(e.getCode(), label(e)))
                                .toList());
                options.add(new Option(ABSENT, "결석(미태깅)"));
                options.add(new Option(LATE_RETURN, "지각 복귀(예정 시각 초과)"));
                yield List.copyOf(options);
            }
            case DAILY_ROUTINE -> Arrays.stream(RoutineResultStatus.values())
                    .map(s -> new Option(s.name(), routineLabel(s)))
                    .toList();
            case REGULAR_SCHEDULE -> List.of(new Option(NOT_RECOGNIZED, "미인정"));
        };
    }

    /** 넣을 수 있는 값인가. 대소문자는 가리지 않는다 — 엔진 비교도 그렇다. */
    public static boolean isValid(PenaltyTriggerType triggerType, String condition) {
        return condition != null && optionsOf(triggerType).stream()
                .anyMatch(o -> o.value().equalsIgnoreCase(condition.trim()));
    }

    /** 오류 메시지에 붙일 허용값 목록. */
    public static String allowedValues(PenaltyTriggerType triggerType) {
        return String.join(", ", optionsOf(triggerType).stream().map(Option::value).toList());
    }

    private static String label(AttendanceEventType event) {
        return switch (event) {
            case CHECK_IN -> "등원";
            case CHECK_OUT -> "하원";
            case LATE -> "지각";
            case OUTING -> "외출";
            case EXCUSED_OUTING -> "사유 외출";
            case EARLY_LEAVE -> "조퇴";
            case RETURN -> "복귀";
        };
    }

    private static String routineLabel(RoutineResultStatus status) {
        return switch (status) {
            case PLANNED -> "예정";
            case DISTRIBUTED -> "배부";
            case SUBMITTED -> "제출";
            case REVIEWED -> "검수 완료";
            case PUBLISHED -> "앱 노출";
            case NOT_SUBMITTED -> "미제출";
            case ABSENT -> "결시";
        };
    }

    /**
     * @param value 저장되는 값. 그대로 {@code trigger_condition}에 들어간다
     * @param label 화면에 보이는 이름
     */
    public record Option(String value, String label) {
    }

    static String normalize(String condition) {
        return condition == null ? null : condition.trim().toUpperCase(Locale.ROOT);
    }
}
