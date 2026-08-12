package com.dlab.domain.penalty.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상벌점 자동부여 규칙. <b>규칙을 코드가 아니라 데이터로 둔다</b> —
 * 트리거→점수 매핑(I-5)이 미확정이라 코드에 박으면 확정 시 전부 갈아엎어야 한다.
 * {@code approval_item}을 데이터로 뺀 것과 같은 이유다.
 *
 * <p><b>{@code active}는 기본 {@code false}다.</b> 규칙 행이 들어와도 명시적으로 켜기
 * 전까지는 아무 점수도 부여되지 않는다 — 미검증 규칙이 실수로 도는 걸 막는다.
 */
@Getter
@Entity
@Table(name = "penalty_rule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PenaltyRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private PenaltyTriggerType triggerType;

    /**
     * 트리거 조건. <b>현재는 단순 코드 매칭으로 본다</b>(출결이면 {@code att_gn} 값,
     * 루틴이면 결과 상태). I-5가 확정되면서 "지각 3회 이상" 같은 조건식이 필요해지면
     * 파서를 붙여야 한다 — 그때까지 값의 의미를 임의로 확장하지 말 것.
     */
    @Column(name = "trigger_condition", nullable = false, length = 100)
    private String triggerCondition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "penalty_item_id", nullable = false)
    private PenaltyItem penaltyItem;

    @Column(nullable = false)
    private boolean active;

    public PenaltyRule(Academy academy, short year, PenaltyTriggerType triggerType,
                       String triggerCondition, PenaltyItem penaltyItem) {
        this.academy = academy;
        this.year = year;
        this.triggerType = triggerType;
        this.triggerCondition = triggerCondition;
        this.penaltyItem = penaltyItem;
        this.active = false;
    }

    /** 규칙 수정. 트리거·조건·연결 항목을 바꾼다. */
    public void change(PenaltyTriggerType triggerType, String triggerCondition,
                       PenaltyItem penaltyItem) {
        this.triggerType = triggerType;
        this.triggerCondition = triggerCondition;
        this.penaltyItem = penaltyItem;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 단순 코드 매칭. 대소문자는 무시한다. */
    public boolean matches(String condition) {
        return triggerCondition.equalsIgnoreCase(condition);
    }

    /**
     * 전년도 복사 원본. NULL이면 그 해에 새로 만든 것이다.
     * 복사본과 신규 생성분을 구분할 유일한 근거라 복사 시 반드시 채운다.
     */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }

}
