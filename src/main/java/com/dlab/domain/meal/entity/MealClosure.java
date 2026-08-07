package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식 중단일 — <b>공휴일과 다르다</b>.
 *
 * <p>학원은 여는데 급식만 안 하는 날이다. {@code holiday} 테이블에 섞으면
 * 그날 학습계획·출결까지 같이 죽는다.
 *
 * <p>사유는 화면 칩 그대로 받는다 — 학원 휴무 / 급식업체 휴무 / 단축수업 / 모의고사 / 자체 행사.
 */
@Getter
@Entity
@Table(name = "meal_closure")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealClosure extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(name = "closure_date", nullable = false)
    private LocalDate closureDate;

    @Column(nullable = false, length = 50)
    private String reason;

    public MealClosure(Academy academy, short year, LocalDate closureDate, String reason) {
        this.academy = academy;
        this.year = year;
        this.closureDate = closureDate;
        this.reason = reason;
    }
}
