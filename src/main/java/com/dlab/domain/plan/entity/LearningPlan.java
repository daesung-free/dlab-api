package com.dlab.domain.plan.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 하루치 학습계획 (F-4.11 / 앱 A-12).
 *
 * <p><b>축은 날짜다.</b> 주간 뷰는 7일을 묶어 보여주는 것일 뿐 저장 단위가 아니다 —
 * 주 단위로 저장하면 "지난 주 계획 불러오기"가 통째 복사가 되어 요일 하나만 가져올 수 없다.
 *
 * <p><b>교시 개념이 없다.</b> 0803 답변서에서 교시×요일 그리드가 폐기되고 순번 기반
 * 자유 입력으로 바뀌었다. {@code period_master}와 엮지 말 것 — 교시는 순공시간 산출에서만
 * 쓰는 별개 개념으로 남는다.
 *
 * <p><b>주말도 대상이다.</b> 이 학원은 토요일에도 운영한다 — 휴일 판정으로 날짜를 막지 말 것.
 */
@Getter
@Entity
@Table(name = "learning_plan")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningPlan extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    /** "지난 주 계획 불러오기"의 원본. {@code null}이면 직접 만든 날이다. */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = false)
    private final List<LearningPlanItem> items = new ArrayList<>();

    public LearningPlan(StudentEnrollment enrollment, LocalDate planDate) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.planDate = planDate;
    }

    /** 항목 입력값. 순번은 들어 있지 않다 — 서버가 매긴다. */
    public record ItemInput(LocalTime startTime, short durationMinutes,
                            LearningPlanOption subject, LearningPlanOption studyType,
                            String material) {

        int startMinute() {
            return startTime.toSecondOfDay() / 60;
        }

        int endMinute() {
            return startMinute() + durationMinutes;
        }
    }

    /**
     * 하루치를 통째로 다시 쓴다.
     *
     * <p><b>줄 단위 수정 API를 두지 않은 이유</b> — 순번이 시작시각 순으로 재부여되므로
     * 한 줄만 고쳐도 나머지 순번이 따라 움직인다. 줄 단위로 열면 클라이언트가 그 재부여를
     * 스스로 맞춰야 하고, 어긋나면 화면 순서와 저장된 순번이 갈린다.
     *
     * <p><b>이행 체크는 살린다.</b> 통째 교체라고 {@code done}을 버리면, 오후에 계획 한 줄을
     * 추가한 것만으로 오전에 체크한 것이 전부 풀린다. 시작시각·소요시간·과목이 모두 같은
     * 줄을 같은 줄로 본다.
     */
    public void replaceItems(List<ItemInput> inputs) {
        rejectOverlap(inputs);

        List<LearningPlanItem> previous = activeItems();
        items.forEach(LearningPlanItem::markDeleted);

        List<ItemInput> ordered = inputs.stream()
                .sorted(Comparator.comparing(ItemInput::startTime))
                .toList();

        short sequence = 1;
        for (ItemInput input : ordered) {
            LearningPlanItem item = new LearningPlanItem(this, sequence++, input.startTime(),
                    input.durationMinutes(), input.subject(), input.studyType(), input.material());
            carryOverDone(previous, input, item);
            items.add(item);
        }
    }

    /** 같은 줄이면 이행 체크를 물려받는다. 못 찾으면 새 줄이라 체크 없이 시작한다. */
    private void carryOverDone(List<LearningPlanItem> previous, ItemInput input,
                               LearningPlanItem item) {
        previous.stream()
                .filter(p -> p.isDone()
                        && p.getStartTime().equals(input.startTime())
                        && p.getDurationMinutes() == input.durationMinutes()
                        && p.getSubject().getId().equals(input.subject().getId()))
                .findFirst()
                .ifPresent(p -> item.mark(true, p.getDoneAt()));
    }

    /**
     * 시간이 겹치는 항목을 거절한다.
     *
     * <p>겹침을 허용하면 <b>과목별 누적 시간이 실제보다 부풀어</b> 통계가 신뢰를 잃는다.
     * 같은 시간에 두 가지를 한다는 입력은 실수인 경우가 대부분이라, 조용히 받아 두는 것보다
     * 그 자리에서 알려주는 편이 낫다.
     *
     * <p>분 단위 정수로 비교한다 — {@code LocalTime} 덧셈은 자정을 넘으면 앞으로 돌아가
     * 겹침 판정이 뒤집힌다.
     */
    private void rejectOverlap(List<ItemInput> inputs) {
        List<ItemInput> ordered = inputs.stream()
                .sorted(Comparator.comparing(ItemInput::startTime))
                .toList();

        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).startMinute() < ordered.get(i - 1).endMinute()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "시간이 겹치는 계획이 있습니다: %s, %s"
                                .formatted(ordered.get(i - 1).startTime(), ordered.get(i).startTime()));
            }
        }
    }

    public List<LearningPlanItem> activeItems() {
        return items.stream()
                .filter(i -> !i.isDeleted())
                .sorted(Comparator.comparing(LearningPlanItem::getSequence))
                .toList();
    }

    /** 복사본 표시. 원본을 남겨두면 복사가 잘못 걸렸을 때 되짚을 수 있다. */
    public void markCopiedFrom(LearningPlan source) {
        this.copiedFromId = source.getId();
    }

    public int totalMinutes() {
        return activeItems().stream().mapToInt(LearningPlanItem::getDurationMinutes).sum();
    }

    public int doneMinutes() {
        return activeItems().stream().filter(LearningPlanItem::isDone)
                .mapToInt(LearningPlanItem::getDurationMinutes).sum();
    }

    public long doneCount() {
        return activeItems().stream().filter(LearningPlanItem::isDone).count();
    }
}
