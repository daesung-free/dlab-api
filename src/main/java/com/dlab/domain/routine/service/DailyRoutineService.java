package com.dlab.domain.routine.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.routine.entity.DailyRoutine;
import com.dlab.domain.routine.entity.DailyRoutineResult;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.penalty.service.PenaltyRuleEngine;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import com.dlab.domain.routine.repository.DailyRoutineRepository;
import com.dlab.domain.routine.repository.DailyRoutineResultRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 데일리 루틴 (F-4.11-1 관리 · 앱 A-11 표시).
 *
 * <p><b>오프라인 시험지 기반이다</b> — 현장 배부 → 학생 가채점 → 교사 검수 → 웹 입력 → 앱 노출.
 * 앱에는 채점·입력 UI가 없다(A-11 "조회 전용").
 *
 * <p>▷[0803] 이 결과가 앱 Daily Report의 <b>'데일리테스트' 원천</b>이다(영단어시험 폐기).
 *
 * <p>⚠️ <b>상벌점 자동 연동은 아직 붙이지 않았다.</b> 시트가
 * *"결과 입력 시 PenaltyRuleEngine.apply() 자동 연동"*을 요구하지만
 * <b>트리거→점수 매핑(I-5)이 미확정</b>이라 규칙 자체가 없다. 규칙 테이블에 행이 채워지면
 * 엔진이 알아서 도는 구조이므로, 지금은 결과만 정확히 쌓아두면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyRoutineService {

    private final DailyRoutineRepository routineRepository;
    private final DailyRoutineResultRepository resultRepository;
    private final AcademyRepository academyRepository;
    private final ClassMasterRepository classMasterRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final PenaltyRuleEngine penaltyRuleEngine;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /** 결과 일괄 입력 한 줄. 반 단위 그리드가 이 목록을 통째로 보낸다. */
    public record ResultInput(Long enrollmentId, RoutineResultStatus status,
                              Short selfScore, Short reviewedScore, String memo) {
    }

    // ── 세팅 (F-4.11-1) ──────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DailyRoutine> findByMonth(Long academyId, short year, short month,
                                          AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return routineRepository
                .findByAcademyIdAndYearAndMonthAndDeletedFalseOrderBySortOrderAscIdAsc(
                        academyId, year, month);
    }

    @Transactional
    public DailyRoutine create(Long academyId, short year, short month, Long classId,
                               String name, String subject, short maxScore, boolean recommended,
                               short sortOrder, AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        ClassMaster classMaster = classId == null ? null
                : classMasterRepository.findById(classId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));

        return routineRepository.save(new DailyRoutine(academy, year, month, classMaster,
                name, subject, maxScore, recommended, sortOrder));
    }

    @Transactional
    public DailyRoutine update(Long routineId, String name, String subject, Short maxScore,
                               Boolean recommended, Short sortOrder, AuthPrincipal principal) {
        DailyRoutine routine = require(routineId, principal);
        routine.update(name, subject, maxScore, recommended, sortOrder);
        return routine;
    }

    @Transactional
    public void delete(Long routineId, AuthPrincipal principal) {
        require(routineId, principal).markDeleted();
    }

    /**
     * 전월 복사.
     *
     * <p><b>대상 월에 이미 세팅이 있으면 거부한다.</b> 덮어쓰면 그 달에 손으로 고친 것이
     * 통째로 사라지고, 되돌릴 방법이 없다 — 전년도 복사(마스터)와 같은 원칙이다.
     *
     * @return 복사된 개수
     */
    @Transactional
    public int copyFromPreviousMonth(Long academyId, short year, short month,
                                     AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (routineRepository.existsByAcademyIdAndYearAndMonthAndDeletedFalse(
                academyId, year, month)) {
            throw new BusinessException(ErrorCode.ROUTINE_TARGET_MONTH_NOT_EMPTY);
        }

        YearMonth previous = YearMonth.of(year, month).minusMonths(1);
        List<DailyRoutine> sources = routineRepository
                .findByAcademyIdAndYearAndMonthAndDeletedFalseOrderBySortOrderAscIdAsc(
                        academyId, (short) previous.getYear(), (short) previous.getMonthValue());
        if (sources.isEmpty()) {
            throw new BusinessException(ErrorCode.ROUTINE_SOURCE_MONTH_EMPTY);
        }

        sources.forEach(source -> routineRepository.save(source.copyTo(year, month)));
        log.info("데일리루틴 전월 복사: {}-{} → {}-{}, {}건",
                previous.getYear(), previous.getMonthValue(), year, month, sources.size());
        return sources.size();
    }

    // ── 결과 (반 단위 그리드) ────────────────────────────────────

    /**
     * 반 단위 그리드 조회.
     *
     * <p><b>결과 행이 없는 학생도 빈 줄로 나온다.</b> 시트가 *"반 단위 그리드 일괄 입력 필수
     * (개별 폼 금지)"*를 요구하는데, 행이 있는 학생만 보이면 아직 아무것도 입력 안 한
     * 초기 상태에서 그리드가 텅 비어 입력을 시작할 수 없다.
     */
    @Transactional(readOnly = true)
    public List<DailyRoutineResult> grid(Long routineId, LocalDate date, AuthPrincipal principal) {
        DailyRoutine routine = require(routineId, principal);
        List<DailyRoutineResult> existing = resultRepository.findGrid(routineId, date);
        if (!existing.isEmpty()) {
            return existing;
        }
        // 첫 조회 — 대상 학생 전원의 빈 행을 만들어 둔다
        return targetsOf(routine).stream()
                .map(enrollment -> new DailyRoutineResult(routine, enrollment, date))
                .toList();
    }

    /**
     * 그날의 <b>모든 루틴</b> 결과 — 학생 × 루틴 매트릭스.
     *
     * <p><b>왜 루틴별 조회로는 안 되는가.</b> 화면은 한 표에 루틴이 컬럼으로 펼쳐진다.
     * 루틴 하나씩 부르면 루틴이 6개면 6번, 월 20개면 20번이 나가고 프론트가 조립까지 해야 한다.
     *
     * <p><b>결과가 없는 칸도 빈 값으로 나온다.</b> 행이 있는 것만 주면 "아직 입력 안 함"과
     * "그 학생은 대상이 아님"이 구분되지 않는다 — 대상 학생은 {@code rows}에 들어오고
     * 해당 루틴 칸이 비어 있으면 미입력이다.
     */
    @Transactional(readOnly = true)
    public DayMatrix matrix(AuthPrincipal principal, Long requestedAcademyId, LocalDate date) {
        Long academyId = principal.requireAcademyScope(requestedAcademyId);

        List<DailyRoutine> routines = routineRepository
                .findByAcademyIdAndYearAndMonthAndDeletedFalseOrderBySortOrderAscIdAsc(
                        academyId, (short) date.getYear(), (short) date.getMonthValue());
        if (routines.isEmpty()) {
            return new DayMatrix(List.of(), List.of());
        }

        // 루틴마다 결과를 조회하면 쿼리가 루틴 수만큼 나간다. ID 를 모아 한 번에 읽는다
        List<Long> routineIds = routines.stream().map(DailyRoutine::getId).toList();
        Map<Long, Map<Long, DailyRoutineResult>> byRoutine = new java.util.HashMap<>();
        for (DailyRoutineResult r : resultRepository.findGridAll(routineIds, date)) {
            byRoutine.computeIfAbsent(r.getRoutine().getId(), k -> new java.util.HashMap<>())
                    .put(r.getEnrollment().getId(), r);
        }

        // 대상 학생은 루틴마다 다르다(반 지정 루틴이 섞인다). 합집합을 행으로 둔다
        Map<Long, StudentEnrollment> targets = new java.util.LinkedHashMap<>();
        Map<Long, java.util.Set<Long>> targetIdsByRoutine = new java.util.HashMap<>();
        for (DailyRoutine routine : routines) {
            java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
            for (StudentEnrollment e : targetsOf(routine)) {
                targets.putIfAbsent(e.getId(), e);
                ids.add(e.getId());
            }
            targetIdsByRoutine.put(routine.getId(), ids);
        }

        Map<Long, String> classNames = new java.util.HashMap<>();
        if (!targets.isEmpty()) {
            classAssignmentRepository.findActiveFixedByEnrollmentIds(targets.keySet())
                    .forEach(ca -> classNames.put(ca.getEnrollment().getId(),
                            ca.getClassMaster().getName()));
        }

        List<MatrixRow> rows = targets.values().stream()
                .map(e -> new MatrixRow(e.getId(), e.getStudentNo(),
                        e.getStudent().getName(), classNames.get(e.getId()),
                        routines.stream()
                                .filter(rt -> targetIdsByRoutine
                                        .getOrDefault(rt.getId(), java.util.Set.of())
                                        .contains(e.getId()))
                                .map(rt -> new MatrixCell(rt.getId(),
                                        byRoutine.getOrDefault(rt.getId(), Map.of()).get(e.getId())))
                                .toList()))
                .toList();

        return new DayMatrix(routines, rows);
    }

    /** @param result {@code null}이면 아직 입력하지 않은 칸이다 */
    public record MatrixCell(Long routineId, DailyRoutineResult result) {
    }

    /** @param cells 그 학생이 <b>대상인 루틴만</b> 들어온다 */
    public record MatrixRow(Long enrollmentId, String studentNo, String studentName,
                            String className, List<MatrixCell> cells) {
    }

    public record DayMatrix(List<DailyRoutine> routines, List<MatrixRow> rows) {
    }

    /**
     * 결과 일괄 입력.
     *
     * <p><b>가채점과 검수 점수를 따로 넣는다.</b> 같은 칸에 덮어쓰면 "학생이 몇 점이라고
     * 했는지"가 사라지는데, 그 차이 자체가 확인 대상이다(시트 "분리 보관").
     *
     * @return 저장된 건수
     */
    @Transactional
    public int saveResults(Long routineId, LocalDate date, List<ResultInput> inputs,
                           AuthPrincipal principal) {
        DailyRoutine routine = require(routineId, principal);
        Instant now = Instant.now(clock);

        for (ResultInput input : inputs) {
            DailyRoutineResult result = resultRepository
                    .findByRoutineIdAndEnrollmentIdAndResultDate(
                            routineId, input.enrollmentId(), date)
                    .orElseGet(() -> resultRepository.save(new DailyRoutineResult(
                            routine, requireEnrollment(input.enrollmentId()), date)));

            verifyScore(routine, input.selfScore());
            verifyScore(routine, input.reviewedScore());

            switch (input.status()) {
                case DISTRIBUTED -> result.distribute();
                case SUBMITTED -> result.submit(input.selfScore());
                case REVIEWED -> result.review(input.reviewedScore(), input.memo(), now);
                case PUBLISHED -> {
                    result.review(input.reviewedScore(), input.memo(), now);
                    result.publish();
                }
                case NOT_SUBMITTED -> result.markNotSubmitted();
                case ABSENT -> result.markAbsent();
                case PLANNED -> { /* 초기 상태 유지 */ }
            }
        }
        return inputs.size();
    }

    /**
     * 일괄 공개.
     *
     * <p><b>검수까지 끝난 것만 연다.</b> 반 전체를 채점한 뒤 한 번에 여는 흐름이라,
     * 중간에 노출되면 "누구는 나왔는데 나는 왜 없냐"가 된다.
     *
     * @return 공개된 건수
     */
    @Transactional
    public int publishAll(Long routineId, LocalDate date, AuthPrincipal principal) {
        require(routineId, principal);
        List<DailyRoutineResult> reviewed = resultRepository
                .findByRoutineIdAndResultDateAndStatusAndDeletedFalse(
                        routineId, date, RoutineResultStatus.REVIEWED);
        reviewed.forEach(DailyRoutineResult::publish);

        // ★ 자동 상벌점은 공개 시점에 건다 — 검수 중인 결과로 벌점을 주면
        //   교사가 점수를 고치는 동안 학생에게 먼저 벌점이 보인다.
        //   조건값은 결과 상태(NOT_SUBMITTED·ABSENT 등)이고, 멱등키가
        //   (학생:일자:규칙)이라 다시 공개해도 늘지 않는다
        reviewed.forEach(result -> penaltyRuleEngine.apply(result.getEnrollment(),
                PenaltyTriggerType.DAILY_ROUTINE, result.getStatus().name(), date));

        log.info("데일리루틴 일괄 공개: routineId={}, date={}, {}건", routineId, date, reviewed.size());
        return reviewed.size();
    }

    // ── 앱 (A-11) ────────────────────────────────────────────────

    /**
     * 오늘의 루틴 — 학생 앱.
     *
     * <p>그 학생에게 <b>적용되는</b> 루틴(반 지정분 + 지점 공통분)을 모두 내리고,
     * 결과가 있으면 붙인다. 결과가 없으면 아직 안 한 항목이다.
     */
    @Transactional(readOnly = true)
    public List<TodayRoutine> today(Long enrollmentId, LocalDate date) {
        StudentEnrollment enrollment = requireEnrollment(enrollmentId);
        // 고정반·이동수업반 둘 다 본다 — 반별 루틴이 어느 쪽에 걸릴지 모른다
        List<Long> classIds = classAssignmentRepository.findByEnrollmentIdAndActiveTrue(enrollmentId)
                .stream().map(ca -> ca.getClassMaster().getId()).toList();

        List<DailyRoutineResult> results =
                resultRepository.findByStudentAndDate(enrollmentId, date);

        return routineRepository.findForStudent(
                        enrollment.getAcademy().getId(),
                        (short) date.getYear(), (short) date.getMonthValue(),
                        classIds.isEmpty() ? List.of(-1L) : classIds)
                .stream()
                .map(routine -> {
                    DailyRoutineResult result = results.stream()
                            .filter(r -> r.getRoutine().getId().equals(routine.getId()))
                            .findFirst().orElse(null);
                    return new TodayRoutine(routine, result);
                })
                .toList();
    }

    /**
     * 학생 화면 한 줄.
     *
     * <p><b>점수는 공개된 것만 내린다</b>({@code isVisibleToStudent}). 검수 중인 점수가
     * 새어나가면 교사가 고치기 전 값이 학생에게 보인다.
     */
    public record TodayRoutine(DailyRoutine routine, DailyRoutineResult result) {

        public RoutineResultStatus status() {
            return result == null ? RoutineResultStatus.PLANNED : result.getStatus();
        }

        public Short visibleScore() {
            if (result == null || !result.getStatus().isVisibleToStudent()) {
                return null;
            }
            return result.getReviewedScore();
        }
    }

    /** 기간 결과 — Daily Report의 "데일리테스트 횟수"가 쓴다. */
    @Transactional(readOnly = true)
    public List<DailyRoutineResult> studentResults(Long enrollmentId, LocalDate from, LocalDate to) {
        return resultRepository.findByStudentAndPeriod(enrollmentId, from, to);
    }

    // ─────────────────────────────────────────────────────────────

    /** 이 루틴의 대상 학생. 반 지정이면 그 반, 아니면 지점 전체다. */
    private List<StudentEnrollment> targetsOf(DailyRoutine routine) {
        if (routine.getClassMaster() != null) {
            return classAssignmentRepository.findActiveByClassId(routine.getClassMaster().getId())
                    .stream().map(com.dlab.domain.user.entity.ClassAssignment::getEnrollment).toList();
        }
        return enrollmentRepository.findCurrentByAcademyAndYear(
                routine.getAcademy().getId(), routine.getYear());
    }

    /** 만점을 넘는 점수는 오타다. 통과시키면 통계가 조용히 틀어진다. */
    private void verifyScore(DailyRoutine routine, Short score) {
        if (score == null) {
            return;
        }
        if (score < 0 || (routine.isScored() && score > routine.getMaxScore())) {
            throw new BusinessException(ErrorCode.ROUTINE_SCORE_OUT_OF_RANGE,
                    "점수는 0~%d 사이여야 합니다.".formatted(routine.getMaxScore()));
        }
    }

    private DailyRoutine require(Long routineId, AuthPrincipal principal) {
        DailyRoutine routine = routineRepository.findById(routineId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTINE_NOT_FOUND));
        verifyAccess(routine.getAcademy().getId(), principal);
        return routine;
    }

    private StudentEnrollment requireEnrollment(Long enrollmentId) {
        return enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (principal != null && !principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
