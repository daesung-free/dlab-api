package com.dlab.domain.plan.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 계획 한 줄.
 *
 * <p><b>순번은 학생이 정하지 않는다.</b> 시작시각 순으로 {@link LearningPlan}이 매긴다 —
 * 요구사항이 "입력 개수만큼 순번 자동 부여"이고, 직접 관리하면 중간에 하나 끼워 넣을 때마다
 * 뒷번호를 전부 고쳐야 한다.
 *
 * <p><b>끝시각이 아니라 소요시간을 저장한다.</b> 입력 단위가 "몇 분 할 것인가"이고,
 * 끝시각으로 두면 자정을 넘는 항목에서 끝 &lt; 시작이 되어 계산이 깨진다.
 *
 * <p><b>이행은 O/X 2단계다</b>(I-19 확정). 부분이행 표현이 없다.
 */
@Getter
@Entity
@Table(name = "learning_plan_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningPlanItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private LearningPlan plan;

    @Column(nullable = false)
    private short sequence;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "duration_minutes", nullable = false)
    private short durationMinutes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_option_id", nullable = false)
    private LearningPlanOption subject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_type_option_id", nullable = false)
    private LearningPlanOption studyType;

    @Column(length = 200)
    private String material;

    @Column(nullable = false)
    private boolean done = false;

    @Column(name = "done_at")
    private Instant doneAt;

    LearningPlanItem(LearningPlan plan, short sequence, LocalTime startTime, short durationMinutes,
                     LearningPlanOption subject, LearningPlanOption studyType, String material) {
        this.plan = plan;
        this.sequence = sequence;
        this.startTime = startTime;
        this.durationMinutes = durationMinutes;
        this.subject = subject;
        this.studyType = studyType;
        this.material = material;
    }

    void assignSequence(short sequence) {
        this.sequence = sequence;
    }

    /**
     * 이행 체크.
     *
     * <p><b>되돌릴 수 있다.</b> 잘못 누른 것을 못 되돌리면 그날 통계가 영영 틀린 채로 남는다.
     * 되돌리면 시각도 지운다 — 남겨두면 "체크 안 했는데 체크한 시각이 있는" 행이 된다.
     */
    public void mark(boolean done, Instant now) {
        this.done = done;
        this.doneAt = done ? now : null;
    }

    /** 끝시각. 저장하지 않고 매번 파생한다 — 자정을 넘으면 다음 날로 넘어간 값이 된다. */
    public LocalTime endTime() {
        return startTime.plusMinutes(durationMinutes);
    }
}
