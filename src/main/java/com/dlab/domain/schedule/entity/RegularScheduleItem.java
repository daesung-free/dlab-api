package com.dlab.domain.schedule.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 정기일정 한 줄 (F-4.1-7).
 *
 * <p><b>요일 반복이다.</b> 날짜를 하나씩 넣지 않는다 — 현강은 "매주 화요일 19시"라
 * 한 달치를 날짜로 펼치면 4~5행이 되고 월이 바뀔 때마다 다시 만들어야 한다.
 * 인정 판정은 그날의 요일로 찾는다.
 */
@Getter
@Entity
@Table(name = "regular_schedule_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RegularScheduleItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private RegularSchedule schedule;

    /** ISO-8601: 1=월 … 7=일. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    /** 나가는 시각. 이 시각과 실제 외출 태깅을 대조해 인정 여부를 가른다. */
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 100)
    private String place;

    public RegularScheduleItem(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                               String title, String place) {
        this.dayOfWeek = (short) dayOfWeek.getValue();
        this.startTime = startTime;
        this.endTime = endTime;
        this.title = title;
        this.place = place;
    }

    void assignTo(RegularSchedule schedule) {
        this.schedule = schedule;
    }

    public DayOfWeek dayOfWeekValue() {
        return DayOfWeek.of(dayOfWeek);
    }

    /** 요일·시간대가 겹치는가. 같은 달에 겹치는 줄을 두 개 두면 어느 것으로 판정할지 모른다. */
    public boolean overlaps(RegularScheduleItem other) {
        return dayOfWeek == other.dayOfWeek
                && startTime.isBefore(other.endTime)
                && other.startTime.isBefore(endTime);
    }
}
