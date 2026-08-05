package com.dlab.domain.period.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 교시 마스터 (지점 공통).
 *
 * <p><b>출결 판정과 학습계획 그리드가 같은 마스터를 쓴다.</b> 두 벌로 만들면
 * 교시를 바꿨을 때 한쪽만 반영돼 "시간표엔 있는데 태깅은 거부되는" 상태가 된다.
 *
 * <p>{@code day_type}이 없으면 <b>토요일마다 전원이 {@code code 113}</b>을 받는다 —
 * 평일 교시표로는 토요일 시각이 어느 교시에도 안 걸리기 때문이다.
 */
@Getter
@Entity
@Table(name = "period_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PeriodMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(name = "period_no", nullable = false)
    private short periodNo;

    @Column(length = 30)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType = DayType.WEEKDAY;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_type", nullable = false, length = 20)
    private PeriodType periodType = PeriodType.CLASS;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** 학습계획 입력 허용 여부. 0723 확정으로 점심·저녁도 허용이라 기본 true다. */
    @Column(nullable = false)
    private boolean planable = true;

    public PeriodMaster(Academy academy, short year, short periodNo, String name,
                        DayType dayType, PeriodType periodType,
                        LocalTime startTime, LocalTime endTime) {
        this.academy = academy;
        this.year = year;
        this.periodNo = periodNo;
        this.name = name;
        this.dayType = dayType;
        this.periodType = periodType;
        this.startTime = startTime;
        this.endTime = endTime;
        this.planable = true;
    }

    public boolean covers(LocalTime time) {
        return !time.isBefore(startTime) && time.isBefore(endTime);
    }
}
