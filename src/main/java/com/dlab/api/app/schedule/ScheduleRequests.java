package com.dlab.api.app.schedule;

import com.dlab.domain.schedule.service.RegularScheduleService.ItemInput;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;

/** 정기일정 등록·수정 요청 (F-4.1-7 · A-7). */
public final class ScheduleRequests {

    /** {@code yyyy-MM}. 전 엔드포인트가 같은 형식을 쓴다. */
    private static final String MONTH_PATTERN = "\\d{4}-(0[1-9]|1[0-2])";

    private ScheduleRequests() {
    }

    /**
     * @param month 대상 월({@code yyyy-MM}). 지난 달은 거절된다 —
     *              사후 인정 통로가 되면 사유신청이 무의미해진다
     */
    public record ScheduleSubmit(@NotBlank(message = "월은 필수입니다.")
                         @Pattern(regexp = MONTH_PATTERN,
                                 message = "월은 yyyy-MM 형식이어야 합니다.") String month,
                         @NotEmpty @Valid List<ScheduleItemInput> items) {

        public YearMonth yearMonth() {
            return YearMonth.parse(month);
        }

        public List<ItemInput> toInputs() {
            return items.stream().map(ScheduleItemInput::toInput).toList();
        }
    }

    public record Replace(@NotEmpty @Valid List<ScheduleItemInput> items) {

        public List<ItemInput> toInputs() {
            return items.stream().map(ScheduleItemInput::toInput).toList();
        }
    }

    /** @param dayOfWeek {@code MONDAY} … {@code SUNDAY} */
    public record ScheduleItemInput(@NotNull DayOfWeek dayOfWeek,
                       @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                       @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                       @NotBlank @Size(max = 100) String title,
                       @Size(max = 100) String place) {

        ItemInput toInput() {
            return new ItemInput(dayOfWeek, startTime, endTime, title, place);
        }
    }
}
