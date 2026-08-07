package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 월 접수기간 — <b>대상월별로 다르다</b>(5/18~27에 6월분을 받는다).
 *
 * <p>그래서 {@link MealPolicy}와 한 테이블에 못 넣는다.
 *
 * <p><b>기간 밖에는 신청 화면이 열리지 않는다</b>(F-4.5). 미등록도 닫힘이다 —
 * 급식업체에 식수를 통보해야 하는 일이라, 모르고 열려 있는 쪽이 더 위험하다.
 */
@Getter
@Entity
@Table(name = "meal_order_window")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealOrderWindow extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(name = "target_month", nullable = false)
    private LocalDate targetMonth;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    public MealOrderWindow(Academy academy, short year, YearMonth targetMonth,
                           LocalDate startsOn, LocalDate endsOn) {
        this.academy = academy;
        this.year = year;
        this.targetMonth = targetMonth.atDay(1);
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public void changePeriod(LocalDate startsOn, LocalDate endsOn) {
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public boolean isOpenOn(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public YearMonth month() {
        return YearMonth.from(targetMonth);
    }
}
