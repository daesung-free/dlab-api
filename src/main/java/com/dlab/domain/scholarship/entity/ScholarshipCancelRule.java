package com.dlab.domain.scholarship.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

/**
 * 장학 취소 기준 (지점 × 연도 × 요건).
 *
 * <h2>★ 코드에 박지 않고 데이터로 둔다</h2>
 * 시트가 <i>"지점별, 연도별 상이 할수 있음"</i>이라고 명시했다.
 * {@code penalty_rule}과 같은 방식이다.
 *
 * <h2>★ {@link #active}가 기본 {@code false}다</h2>
 * 행을 넣어도 <b>명시적으로 켜기 전엔 안 돈다.</b> 미검증 규칙이 실수로 돌면
 * 멀쩡한 학생이 검토 대상으로 올라온다.
 */
@Getter
@Entity
@Table(name = "scholarship_cancel_rule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScholarshipCancelRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null} = 전 지점 공통. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 30)
    private CancelRuleType ruleType;

    /** 벌점 40 / 등급합 5 / 미응시 2회. */
    @Column(nullable = false)
    private int threshold;

    /**
     * 등급합 대상 과목(콤마 구분). {@code exam_subject.subject_code}와 같은 값이다.
     *
     * <p>⚠️ 규정에 <b>"3과목"이라고만 있고 어느 과목인지가 없다.</b>
     * 국·수·영으로 추정해 넣어 뒀으나 확인 전까지 규칙을 켜지 않는다.
     */
    @Column(name = "subject_codes", length = 200)
    private String subjectCodes;

    @Column(nullable = false)
    private boolean active = false;

    public ScholarshipCancelRule(Academy academy, short year, CancelRuleType ruleType,
                                 int threshold, String subjectCodes) {
        this.academy = academy;
        this.year = year;
        this.ruleType = ruleType;
        this.threshold = threshold;
        this.subjectCodes = subjectCodes;
        this.active = false;
    }

    /** 등급합 대상 과목 목록. 비어 있으면 판정하지 않는다 — 임의로 고르면 안 된다. */
    public List<String> subjects() {
        if (subjectCodes == null || subjectCodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(subjectCodes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public void update(int threshold, String subjectCodes) {
        this.threshold = threshold;
        this.subjectCodes = subjectCodes;
    }

    /** 켜고 끄기. <b>끄면 판정에서 아예 빠진다</b> — 이미 올라온 검토 대상은 남는다. */
    public void changeActive(boolean active) {
        this.active = active;
    }

    public boolean isCommon() {
        return academy == null;
    }
}
