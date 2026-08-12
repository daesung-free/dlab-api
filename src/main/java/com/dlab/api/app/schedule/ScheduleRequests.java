package com.dlab.api.app.schedule;

import com.dlab.domain.schedule.service.RegularScheduleService.ItemInput;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/** 정기일정 등록·수정 요청 (F-4.1-7 · A-7). */
public final class ScheduleRequests {

    private ScheduleRequests() {
    }

    /**
     * @param month 대상 월. 지난 달은 거절된다 — 사후 인정 통로가 되면 사유신청이 무의미해진다
     */
    public record Submit(@NotNull Short month,
                         @NotEmpty @Valid List<Item> items) {

        public List<ItemInput> toInputs() {
            return items.stream().map(Item::toInput).toList();
        }
    }

    public record Replace(@NotEmpty @Valid List<Item> items) {

        public List<ItemInput> toInputs() {
            return items.stream().map(Item::toInput).toList();
        }
    }

    /** @param dayOfWeek {@code MONDAY} … {@code SUNDAY} */
    public record Item(@NotNull DayOfWeek dayOfWeek,
                       @NotNull @JsonFormat(pattern = "HH:mm") LocalTime startTime,
                       @NotNull @JsonFormat(pattern = "HH:mm") LocalTime endTime,
                       @NotBlank @Size(max = 100) String title,
                       @Size(max = 100) String place) {

        ItemInput toInput() {
            return new ItemInput(dayOfWeek, startTime, endTime, title, place);
        }
    }
}
