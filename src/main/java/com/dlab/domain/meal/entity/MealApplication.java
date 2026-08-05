package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식 신청.
 *
 * <p><b>취소를 물리 삭제하지 않는다.</b> 앱 취소는 3일 전 PG 환불 대상이고 관리자 취소는
 * 기간 제한이 없어(F-4.5), "언제 누가 취소했나"가 정산 근거가 된다.
 *
 * <p>결제 연결은 아직 없다 — PG 스펙(E-3)·데스크 당일신청 결제방식(I-13)이 미확정이다.
 */
@Getter
@Entity
@Table(name = "meal_application")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealApplication extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "meal_date", nullable = false)
    private LocalDate mealDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_type", nullable = false, length = 10)
    private MealType mealType;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    public MealApplication(StudentEnrollment enrollment, LocalDate mealDate, MealType mealType) {
        this.academy = enrollment.getAcademy();
        this.enrollment = enrollment;
        this.mealDate = mealDate;
        this.mealType = mealType;
    }

    public void cancel(Instant at) {
        this.canceledAt = at;
    }

    public boolean isActive() {
        return canceledAt == null;
    }
}
