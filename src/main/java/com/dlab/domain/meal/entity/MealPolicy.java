package com.dlab.domain.meal.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 급식 정책 — 신청·취소 마감 D-n.
 *
 * <p><b>상수로 박지 않는다.</b> 화면이 드롭다운(1/2/3/5/7)으로 고르게 한다.
 * 신청과 취소가 <b>같은 기준</b>을 쓴다(F-4.5 "앱 신청·취소 모두 이용일 D-n까지").
 */
@Getter
@Entity
@Table(name = "meal_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MealPolicy extends BaseEntity {

    /** 미등록 지점의 기본값. 화면 기본 선택도 3일이다. */
    public static final short DEFAULT_DEADLINE_DAYS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(name = "deadline_days", nullable = false)
    private short deadlineDays = DEFAULT_DEADLINE_DAYS;

    public MealPolicy(Academy academy, short year, short deadlineDays) {
        this.academy = academy;
        this.year = year;
        this.deadlineDays = deadlineDays;
    }

    public void changeDeadlineDays(short deadlineDays) {
        this.deadlineDays = deadlineDays;
    }
}
