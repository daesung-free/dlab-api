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

    /**
     * ★★ <b>몇 번째에 부여하는가</b> (디랩 1029 4-6).
     *
     * <p>★ 취침 3회처럼 <b>횟수를 세는 규칙</b>이 쓴다. ⚠️ 1029 지침:
     * <b>「코드에 3을 박지 말 것」</b>
     *
     * <p>★ 조건값({@code SLEEP_3})에 넣지 않은 이유 — <b>3을 2로 바꾸려면 규칙을 새로
     * 만들어야</b> 하고, 그러면 <b>그 전에 부여된 벌점의 근거(규칙 id)가 다른 규칙을
     * 가리킨다.</b> ★ 칸으로 두면 <b>행만 고친다.</b>
     *
     * <p>★ <b>{@code null} 은 세지 않는다는 뜻</b>이다 — 지금 트리거 대부분이 그렇다
     * (건별로 걸린다).
     */
    @Column(name = "threshold")
    private Short threshold;

    /** 임계값을 바꾼다. {@code null} 이면 세지 않는다. */
    public void changeThreshold(Short threshold) {
        if (threshold != null && threshold < 1) {
            throw new IllegalArgumentException("임계값은 1 이상이어야 합니다.");
        }
        this.threshold = threshold;
    }

    /** ★ 횟수를 세는 규칙인가. */
    public boolean counts() {
        return threshold != null;
    }

    /**
     * ★★ 이 규칙이 부여할 때 <b>먼저 거둘 규칙</b> (디랩 1029 4-2).
     *
     * <h2>★ 자동 지각 ↔ 사유 지각 중복 방지</h2>
     * 사유지각은 <b>원장이 {@code LATE}</b> 다. 그래서 「{@code A} → 자동 지각」 규칙을
     * 켜면 <b>사유지각 학생에게도 -1 이 붙고</b>, 승인 트리거가 <b>-1 을 또 붙인다.</b>
     *
     * <h2>⚠️ 코드에 박지 말 것</h2>
     * 「{@code LATE} 면 자동 지각을 지운다」를 코드에 쓰면 <b>디랩이 항목 이름을 바꿀 때
     * 깨진다</b>(1029 4-2 지침). 그래서 <b>데이터로 둔다.</b>
     *
     * <p>★ <b>{@code null} 이면 거두지 않는다</b> — 대부분의 규칙이 그렇다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_rule_id")
    private PenaltyRule supersedesRule;

    /**
     * 대체 대상을 지정한다.
     *
     * <p>⚠️ <b>자기 자신은 안 된다</b> — 부여하기 전에 자기 것을 거두는 모순이 된다
     * (스키마 CHECK 가 같은 것을 막는다).
     */
    public void supersede(PenaltyRule target) {
        if (target != null && target.getId() != null && target.getId().equals(this.id)) {
            throw new IllegalArgumentException("규칙은 자기 자신을 대체할 수 없습니다.");
        }
        this.supersedesRule = target;
    }

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
