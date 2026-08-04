package com.dlab.domain.holiday.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 공휴일.
 *
 * <p>음력 공휴일·대체공휴일·임시공휴일을 계산하지 않고 <b>데이터로 넣는다</b>.
 * 규칙이 해마다 바뀌고 임시공휴일은 규칙 자체가 없기 때문이다(V3 주석 참고).
 */
@Getter
@Entity
@Table(name = "holiday")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holiday extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** null이면 전 지점 공통(법정공휴일). 값이 있으면 그 지점만. */
    @Column(name = "academy_id")
    private Long academyId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "holiday_type", nullable = false, length = 20)
    private HolidayType holidayType;

    private Holiday(Long academyId, LocalDate holidayDate, String name, HolidayType holidayType) {
        this.academyId = academyId;
        this.holidayDate = holidayDate;
        this.name = name;
        this.holidayType = holidayType;
    }

    /** 전 지점 공통 휴일. */
    public static Holiday nationwide(LocalDate date, String name, HolidayType type) {
        return new Holiday(null, date, name, type);
    }

    /** 지점 자체 휴일(개원기념일 등). */
    public static Holiday ofAcademy(Long academyId, LocalDate date, String name) {
        return new Holiday(academyId, date, name, HolidayType.ACADEMY);
    }

    public boolean isNationwide() {
        return academyId == null;
    }
}
