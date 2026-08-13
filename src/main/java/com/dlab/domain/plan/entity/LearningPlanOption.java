package com.dlab.domain.plan.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학습계획 드롭다운 마스터 (과목 · 학습형태).
 *
 * <p><b>과목을 enum으로 두지 않은 이유</b> — 요구사항이 "탐구1/탐구2 분리 + 과목
 * 커스터마이즈"라 지점·연도마다 달라진다. enum이면 과목 하나 늘 때마다 배포가 필요하다.
 *
 * <p>{@code year}가 있어 전년도 복사(YearlySnapshotService) 대상이다.
 */
@Getter
@Entity
@Table(name = "learning_plan_option")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningPlanOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "option_type", nullable = false, length = 20)
    private LearningPlanOptionType optionType;

    @Column(nullable = false, length = 30)
    private String label;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder = 0;

    public LearningPlanOption(Academy academy, short year, LearningPlanOptionType optionType,
                              String label, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.optionType = optionType;
        this.label = label;
        this.sortOrder = sortOrder;
    }

    public void update(String label, short sortOrder) {
        this.label = label;
        this.sortOrder = sortOrder;
    }
}
