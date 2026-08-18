package com.dlab.domain.plan.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.plan.entity.*;
import com.dlab.domain.plan.repository.LearningPlanOptionRepository;
import com.dlab.domain.plan.repository.LearningPlanRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주·일 학습계획 (F-4.11 / 앱 A-12) — 순번 기반.
 *
 * <p><b>주도권은 학생이다.</b> 과목별 시간 배분을 학생이 정하고 담임은 이행 여부·통계만 본다
 * (0803 답변서). 그래서 관리자용 수정 경로가 없다 — 옛 그리드의 "파란색 = 교사 편집분"
 * 개념도 함께 사라졌다.
 *
 * <p><b>주는 월요일에 시작한다.</b> 어느 날짜가 들어와도 그 주의 월요일로 맞춰 다룬다 —
 * 클라이언트가 주 시작을 다르게 계산하면 같은 주가 두 벌로 보인다.
 */
@Service
@RequiredArgsConstructor
public class LearningPlanService {

    private final LearningPlanRepository planRepository;
    private final LearningPlanOptionRepository optionRepository;
    private final Clock clock;

    /** 하루치 항목 입력. 옵션은 라벨이 아니라 ID로 받는다 — 라벨은 바뀔 수 있다. */
    public record ItemCommand(LocalTime startTime, short durationMinutes,
                              Long subjectOptionId, Long studyTypeOptionId, String material) {}

    /** 과목·형태별 집계 한 줄. */
    public record Composition(Long optionId, String label, int plannedMinutes, int doneMinutes) {}

    /** 기간 통계. */
    public record Statistics(int plannedMinutes, int doneMinutes,
                             long totalItems, long doneItems,
                             List<Composition> bySubject, List<Composition> byStudyType) {}

    // ─────────────────────────────────────────────────────────────
    // 조회
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LearningPlan findDay(Long enrollmentId, LocalDate date) {
        return planRepository.findByDate(enrollmentId, date).orElse(null);
    }

    /**
     * 주간 뷰. 계획이 없는 날은 목록에서 빠진다 — 빈 날을 서버가 만들어 두면
     * "아직 안 짠 날"과 "짰다가 다 지운 날"이 구분되지 않는다.
     */
    @Transactional(readOnly = true)
    public List<LearningPlan> findWeek(Long enrollmentId, LocalDate anyDateInWeek) {
        LocalDate monday = weekStart(anyDateInWeek);
        return planRepository.findRange(enrollmentId, monday, monday.plusDays(6));
    }

    // ─────────────────────────────────────────────────────────────
    // 입력
    // ─────────────────────────────────────────────────────────────

    /**
     * 하루치를 통째로 저장한다. 없으면 만들고, 있으면 갈아 끼운다.
     *
     * <p><b>줄 단위 API를 두지 않았다.</b> 순번이 시작시각 순으로 재부여되므로 한 줄만
     * 고쳐도 나머지가 따라 움직인다 — 줄 단위로 열면 클라이언트가 그 재부여를 스스로
     * 맞춰야 하고, 어긋나면 화면 순서와 저장된 순번이 갈린다.
     *
     * <p><b>과거 날짜도 막지 않는다.</b> 계획은 미리 짜는 것이지만 이행 체크와 함께
     * 나중에 정리하는 흐름이 실제로 있고, 막으면 어제 빠뜨린 줄을 영영 못 채운다.
     */
    @Transactional
    public LearningPlan saveDay(StudentEnrollment enrollment, LocalDate date,
                                List<ItemCommand> commands) {
        LearningPlan plan = planRepository.findByDate(enrollment.getId(), date)
                .orElseGet(() -> planRepository.save(new LearningPlan(enrollment, date)));

        plan.replaceItems(toInputs(enrollment, commands));

        // ★ 응답에 항목 ID가 실려야 앱이 곧바로 이행 체크를 걸 수 있다. 플러시하지 않으면
        //   방금 만든 줄의 ID가 전부 null로 나가고, 앱은 화면을 다시 불러와야 한다
        planRepository.flush();
        return plan;
    }

    /**
     * 이행 O/X (I-19 확정 — 부분이행 없음).
     *
     * <p>남의 계획을 체크하지 못하게 <b>등록 건까지 확인</b>한다. 항목 ID만으로 찾으면
     * 다른 학생의 항목 번호를 넣는 것만으로 그 학생 통계가 바뀐다.
     */
    @Transactional
    public LearningPlanItem mark(StudentEnrollment enrollment, LocalDate date,
                                 Long itemId, boolean done) {
        LearningPlan plan = planRepository.findByDate(enrollment.getId(), date)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "학습계획이 없습니다."));

        LearningPlanItem item = plan.activeItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "계획 항목이 없습니다."));

        item.mark(done, clock.instant());
        return item;
    }

    /**
     * "지난 주 계획 그대로 불러오기".
     *
     * <p><b>이미 짜 둔 날은 건너뛴다.</b> 덮어쓰면 이번 주에 먼저 입력해 둔 것이 조용히
     * 사라진다 — 복사는 빈칸을 채우는 기능이지 되돌리기가 아니다.
     *
     * <p><b>이행 체크는 따라오지 않는다.</b> 지난 주에 한 일이 이번 주에 이미 끝난 것으로
     * 표시되면 통계가 통째로 틀어진다.
     *
     * @return 실제로 채워진 날짜
     */
    @Transactional
    public List<LocalDate> copyPreviousWeek(StudentEnrollment enrollment, LocalDate anyDateInWeek) {
        LocalDate target = weekStart(anyDateInWeek);
        LocalDate source = target.minusWeeks(1);

        Map<DayOfWeek, LearningPlan> sourcePlans = new LinkedHashMap<>();
        planRepository.findRange(enrollment.getId(), source, source.plusDays(6))
                .forEach(p -> sourcePlans.put(p.getPlanDate().getDayOfWeek(), p));

        if (sourcePlans.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "지난 주에 저장된 계획이 없습니다.");
        }

        List<LocalDate> filled = new ArrayList<>();
        for (Map.Entry<DayOfWeek, LearningPlan> entry : sourcePlans.entrySet()) {
            LocalDate date = target.with(entry.getKey());
            LearningPlan existing = planRepository.findByDate(enrollment.getId(), date).orElse(null);
            if (existing != null && !existing.activeItems().isEmpty()) {
                continue;
            }

            LearningPlan plan = existing != null
                    ? existing
                    : planRepository.save(new LearningPlan(enrollment, date));
            plan.markCopiedFrom(entry.getValue());
            plan.replaceItems(entry.getValue().activeItems().stream()
                    .map(i -> new LearningPlan.ItemInput(i.getStartTime(), i.getDurationMinutes(),
                            i.getSubject(), i.getStudyType(), i.getMaterial()))
                    .toList());
            filled.add(date);
        }
        return filled;
    }

    // ─────────────────────────────────────────────────────────────
    // 통계
    // ─────────────────────────────────────────────────────────────

    /**
     * 과목·형태별 누적 시간 (계획 / 이행).
     *
     * <p><b>"실제 학습시간"이 아니라 "이행 처리된 계획 시간"이다.</b> 실제 시간을 따로
     * 입력받는 화면이 없으므로 O 체크된 항목의 소요시간을 더한다 — 순공시간(출결 기반)과는
     * 다른 값이고, 같은 것으로 섞어 보여주면 안 된다.
     */
    @Transactional(readOnly = true)
    public Statistics statistics(Long enrollmentId, LocalDate from, LocalDate to) {
        List<LearningPlanItem> items = planRepository.findRange(enrollmentId, from, to).stream()
                .flatMap(p -> p.activeItems().stream())
                .toList();

        return new Statistics(
                items.stream().mapToInt(LearningPlanItem::getDurationMinutes).sum(),
                items.stream().filter(LearningPlanItem::isDone)
                        .mapToInt(LearningPlanItem::getDurationMinutes).sum(),
                items.size(),
                items.stream().filter(LearningPlanItem::isDone).count(),
                compose(items, LearningPlanItem::getSubject),
                compose(items, LearningPlanItem::getStudyType));
    }

    private List<Composition> compose(List<LearningPlanItem> items,
                                      java.util.function.Function<LearningPlanItem,
                                              LearningPlanOption> axis) {
        Map<Long, Composition> acc = new LinkedHashMap<>();
        for (LearningPlanItem item : items) {
            LearningPlanOption option = axis.apply(item);
            acc.merge(option.getId(),
                    new Composition(option.getId(), option.getLabel(),
                            item.getDurationMinutes(), item.isDone() ? item.getDurationMinutes() : 0),
                    (a, b) -> new Composition(a.optionId(), a.label(),
                            a.plannedMinutes() + b.plannedMinutes(),
                            a.doneMinutes() + b.doneMinutes()));
        }
        return List.copyOf(acc.values());
    }

    // ─────────────────────────────────────────────────────────────
    // 드롭다운 마스터
    // ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LearningPlanOption> options(Long academyId, short year) {
        return optionRepository.findAll(academyId, year);
    }

    @Transactional(readOnly = true)
    public List<LearningPlanOption> options(Long academyId, short year,
                                            LearningPlanOptionType type) {
        return optionRepository.findByType(academyId, year, type);
    }

    @Transactional
    public LearningPlanOption createOption(com.dlab.domain.user.entity.Academy academy, short year,
                                           LearningPlanOptionType type, String label,
                                           short sortOrder) {
        optionRepository.findByLabel(academy.getId(), year, type, label).ifPresent(o -> {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 있는 항목입니다: " + label);
        });
        return optionRepository.save(
                new LearningPlanOption(academy, year, type, label, sortOrder));
    }

    @Transactional
    public LearningPlanOption updateOption(Long academyId, Long optionId, String label,
                                           short sortOrder) {
        LearningPlanOption option = requireOption(academyId, optionId);
        optionRepository.findByLabel(academyId, option.getYear(), option.getOptionType(), label)
                .filter(other -> !other.getId().equals(optionId))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 있는 항목입니다: " + label);
                });
        option.update(label, sortOrder);
        return option;
    }

    /**
     * <p><b>soft delete다.</b> 이미 이 과목으로 쌓인 계획이 라벨을 참조하고 있어,
     * 물리 삭제하면 과거 통계에서 그 과목이 통째로 사라진다.
     */
    @Transactional
    public void deleteOption(Long academyId, Long optionId) {
        requireOption(academyId, optionId).markDeleted();
    }

    private LearningPlanOption requireOption(Long academyId, Long optionId) {
        LearningPlanOption option = optionRepository.findById(optionId)
                .filter(o -> !o.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        if (!option.getAcademy().getId().equals(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return option;
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 옵션을 실제 행으로 바꾸면서 검증한다.
     *
     * <p><b>지점·연도·유형을 전부 본다.</b> ID만 믿으면 다른 지점 과목이나 학습형태 ID를
     * 과목 자리에 넣는 것이 통과해, 통계 축이 뒤섞인 채로 저장된다.
     */
    private List<LearningPlan.ItemInput> toInputs(StudentEnrollment enrollment,
                                                  List<ItemCommand> commands) {
        return commands.stream()
                .map(c -> new LearningPlan.ItemInput(
                        c.startTime(), c.durationMinutes(),
                        requireOptionOf(enrollment, c.subjectOptionId(),
                                LearningPlanOptionType.SUBJECT),
                        requireOptionOf(enrollment, c.studyTypeOptionId(),
                                LearningPlanOptionType.STUDY_TYPE),
                        c.material()))
                .toList();
    }

    private LearningPlanOption requireOptionOf(StudentEnrollment enrollment, Long optionId,
                                               LearningPlanOptionType type) {
        LearningPlanOption option = optionRepository.findById(optionId)
                .filter(o -> !o.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));

        if (!option.getAcademy().getId().equals(enrollment.getAcademy().getId())
                || option.getYear() != enrollment.getYear()
                || option.getOptionType() != type) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "선택할 수 없는 항목입니다.");
        }
        return option;
    }

    /** 어느 날짜가 들어와도 그 주의 월요일. */
    public static LocalDate weekStart(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }
}
