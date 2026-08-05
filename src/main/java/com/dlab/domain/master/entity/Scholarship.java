package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 장학. 등록 건 단위다 — 그 해 등록에 붙는 할인이라 기수가 바뀌면 다시 부여한다.
 *
 * <p>할인율은 청구(billing) 계산에 쓰이므로 {@code BigDecimal}로 다룬다.
 * 금액 계산에 {@code double}을 쓰면 반올림 오차가 청구서에 그대로 나간다.
 */
@Getter
@Entity
@Table(name = "scholarship")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Scholarship extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "scholarship_type", nullable = false, length = 20)
    private String scholarshipType;

    @Column(name = "discount_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountRate;

    public Scholarship(Academy academy, StudentEnrollment enrollment,
                       String scholarshipType, BigDecimal discountRate) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.scholarshipType = scholarshipType;
        this.discountRate = discountRate;
    }

    public void update(String scholarshipType, BigDecimal discountRate) {
        this.scholarshipType = scholarshipType;
        this.discountRate = discountRate;
    }
}
