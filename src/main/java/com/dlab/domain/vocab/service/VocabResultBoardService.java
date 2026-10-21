package com.dlab.domain.vocab.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository.ClassRef;
import com.dlab.domain.user.service.HomeroomScopeService;
import com.dlab.domain.vocab.entity.VocabAttempt;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.repository.VocabAttemptRepository;
import com.dlab.domain.vocab.repository.VocabExamRoundRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 결과 조회 (v1 7장).
 *
 * <p>디랩 원문: <i>"결과 조회: 학생별·유닛별·기간별 참여 여부와 점수"</i>,
 * <i>"반·담임 관리: 반 단위 조회, 담임별 담당 학생 결과 조회"</i>.
 *
 * <h2>★ 반·담임을 새로 만들지 않는다</h2>
 * v1 7장 각주가 <i>"반·담임 정보는 디랩 통합관리에 이미 있는 데이터를 쓴다. 새로 만들지
 * 않는다"</i>고 못 박았다. {@link HomeroomScopeService} 를 그대로 쓴다 — 담임 범위 판정을
 * 새로 짜면 <b>출결·루틴 화면과 다른 학생 집합</b>이 나온다.
 *
 * <h2>★★ 미응시와 0점을 구분한다</h2>
 * 이 화면의 목적이 <i>"참여 여부와 점수"</i>라 둘이 섞이면 쓸 수 없다. 점수가
 * {@code null} 이면 <b>안 본 것</b>이고 0 이면 <b>보고 다 틀린 것</b>이다 —
 * 전자는 독려할 일이고 후자는 가르칠 일이다.
 */
@Service
@RequiredArgsConstructor
public class VocabResultBoardService {

    private final VocabExamRoundRepository roundRepository;
    private final VocabAttemptRepository attemptRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final HomeroomScopeService homeroomScopeService;
    /**
     * ★ 반·담임은 <b>이미 있는 조회를 그대로 쓴다</b> (v1 7장 각주: *"반·담임 정보는 디랩
     * 통합관리에 이미 있는 데이터를 쓴다. 새로 만들지 않는다"*).
     *
     * <p>학생마다 따로 조회하지 않는다 — 반 이름은 목록의 한 칸일 뿐인데 학생 수만큼
     * 쿼리가 나가면 집계를 한 번에 한 의미가 없어진다.
     */
    private final LearningPlanBoardRepository classRepository;

    /**
     * 학생 한 줄.
     *
     * @param takenCount  응시한 회차 수. ★ <b>재응시는 세지 않는다</b> — 같은 회차를 두 번
     *                    봤다고 참여율이 200%가 되면 안 된다
     * @param averageScore 정답률(%) 평균. ★ 미응시 회차는 <b>빼고</b> 센다 — 0 으로 넣으면
     *                    늦게 입학한 학생이 전부 바닥이 된다
     */
    public record StudentRow(
            Long enrollmentId,
            String studentNo,
            String studentName,
            Long classId,
            String className,
            Long homeroomTeacherId,
            String homeroomTeacherName,
            int openCount,
            int takenCount,
            Integer participationRate,
            Integer averageScore,
            Integer lastScore,
            LocalDate lastTakenOn) {
    }

    /**
     * 유닛 한 줄 — 어느 유닛이 약한지 본다.
     *
     * <p>★ <b>금요일 누적은 빼고 센다.</b> 누적은 범위가 여러 유닛이라 어느 유닛의 점수인지
     * 가를 수 없고, 넣으면 그 주 유닛들이 전부 같은 값으로 물든다.
     */
    public record UnitRow(short unitNo, int roundCount, int attemptCount,
                          Integer averageScore) {
    }

    /** 회차 한 줄 — 이 회차를 몇 명이 봤는지. */
    public record RoundRow(Long roundId, Integer roundNo, Short cycle, LocalDate openDate,
                           boolean weekly, Short unitFrom, Short unitTo,
                           int targetCount, int attemptCount, Integer averageScore) {
    }

    /**
     * 학생별 결과.
     *
     * @param from 공개일 기준 시작. 비우면 전체
     */
    @Transactional(readOnly = true)
    public List<StudentRow> students(AuthPrincipal me, Long academyId, Short year,
                                     LocalDate from, LocalDate to, Long classId) {
        Long scope = me.requireAcademyScope(academyId);
        var rounds = roundsIn(scope, year, from, to);

        // ★ 휴원·퇴원은 공부하러 오지 않는 상태다. 남기면 참여율 목록이 퇴원생으로 채워진다
        //   (Daily Report 와 같은 판단)
        var targets = enrollmentRepository.findCurrentByAcademyId(scope).stream()
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .filter(e -> year == null || year.equals(e.getYear()))
                .toList();

        // ★ 담임은 맡은 학생만 본다 — 출결·루틴과 같은 판정을 쓴다
        short targetYear = year != null ? year
                : targets.isEmpty() ? (short) LocalDate.now().getYear() : targets.get(0).getYear();
        var filter = homeroomScopeService.resolveStudentFilter(me, targetYear, classId);
        if (filter.blocksEverything()) {
            return List.of();
        }

        Map<Long, ClassRef> classes = classRepository.fixedClasses(scope);

        List<StudentRow> rows = new ArrayList<>();
        for (StudentEnrollment e : targets) {
            if (!filter.matches(e.getId())) {
                continue;
            }
            rows.add(rowOf(e, rounds, classes.get(e.getId())));
        }
        rows.sort(Comparator.comparing(StudentRow::studentNo,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** 유닛별 결과 — ★ 금요일 누적은 제외한다. */
    @Transactional(readOnly = true)
    public List<UnitRow> units(AuthPrincipal me, Long academyId, Short year,
                               LocalDate from, LocalDate to, Long classId) {
        Long scope = me.requireAcademyScope(academyId);
        var rounds = roundsIn(scope, year, from, to).stream()
                .filter(r -> !r.isWeekly())
                .toList();

        short targetYear = year != null ? year : (short) LocalDate.now().getYear();
        var filter = homeroomScopeService.resolveStudentFilter(me, targetYear, classId);
        if (filter.blocksEverything()) {
            return List.of();
        }

        // 유닛 → (회차 수, 점수 목록)
        Map<Short, int[]> roundCount = new LinkedHashMap<>();
        Map<Short, List<Integer>> scores = new HashMap<>();

        for (VocabExamRound round : rounds) {
            // 평일 회차는 범위가 여러 유닛일 수 있다(2·3회독) — 그 범위 전체에 같은 점수를 넣는다
            for (short unit = round.getUnitFrom(); unit <= round.getUnitTo(); unit++) {
                roundCount.computeIfAbsent(unit, k -> new int[1])[0]++;
            }
            for (VocabAttempt attempt : submittedOf(round, filter)) {
                Integer rate = rateOf(attempt);
                if (rate == null) {
                    continue;
                }
                for (short unit = round.getUnitFrom(); unit <= round.getUnitTo(); unit++) {
                    scores.computeIfAbsent(unit, k -> new ArrayList<>()).add(rate);
                }
            }
        }

        return roundCount.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    var list = scores.getOrDefault(entry.getKey(), List.of());
                    return new UnitRow(entry.getKey(), entry.getValue()[0], list.size(),
                            average(list));
                })
                .toList();
    }

    /** 회차별 결과. */
    @Transactional(readOnly = true)
    public List<RoundRow> rounds(AuthPrincipal me, Long academyId, Short year,
                                 LocalDate from, LocalDate to, Long classId) {
        Long scope = me.requireAcademyScope(academyId);
        var rounds = roundsIn(scope, year, from, to);

        short targetYear = year != null ? year : (short) LocalDate.now().getYear();
        var filter = homeroomScopeService.resolveStudentFilter(me, targetYear, classId);
        if (filter.blocksEverything()) {
            return List.of();
        }

        long targetCount = enrollmentRepository.findCurrentByAcademyId(scope).stream()
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .filter(e -> filter.matches(e.getId()))
                .count();

        return rounds.stream().map(round -> {
            var attempts = submittedOf(round, filter);
            var rates = attempts.stream().map(this::rateOf).filter(java.util.Objects::nonNull)
                    .toList();
            return new RoundRow(round.getId(), round.getRoundNo(), round.getCycle(),
                    round.getOpenDate(), round.isWeekly(),
                    round.getUnitFrom(), round.getUnitTo(),
                    (int) targetCount, attempts.size(), average(rates));
        }).toList();
    }

    private StudentRow rowOf(StudentEnrollment e, List<VocabExamRound> rounds, ClassRef clazz) {
        int taken = 0;
        List<Integer> rates = new ArrayList<>();
        Integer lastScore = null;
        LocalDate lastOn = null;

        for (VocabExamRound round : rounds) {
            // ★ 대표 건(최초 제출)만 본다 — 재응시를 세면 참여율이 100%를 넘는다
            var representative = attemptRepository
                    .findFirstByRoundIdAndEnrollmentIdAndSubmittedAtNotNullAndDeletedFalseOrderBySubmittedAtAsc(
                            round.getId(), e.getId())
                    .orElse(null);
            if (representative == null) {
                continue;
            }
            taken++;
            Integer rate = rateOf(representative);
            if (rate != null) {
                rates.add(rate);
            }
            // 공개일 순으로 돌므로 마지막에 남는 것이 가장 최근이다
            if (lastOn == null || !round.getOpenDate().isBefore(lastOn)) {
                lastOn = round.getOpenDate();
                lastScore = rate;
            }
        }

        int open = rounds.size();
        return new StudentRow(e.getId(), e.getStudentNo(),
                e.getStudent() == null ? null : e.getStudent().getName(),
                // 반 미배정 학생이 있다 — 반이 없으면 담임도 없다(담임은 반에 붙어 있다)
                clazz == null ? null : clazz.id(),
                clazz == null ? null : clazz.name(),
                clazz == null ? null : clazz.homeroomTeacherId(),
                clazz == null ? null : clazz.homeroomTeacherName(),
                open, taken,
                // ★ 공개된 회차가 없으면 참여율은 null 이다 — 0% 로 두면 「아무도 안 봤다」로 읽힌다
                open == 0 ? null : taken * 100 / open,
                average(rates), lastScore, lastOn);
    }

    private List<VocabAttempt> submittedOf(VocabExamRound round,
                                           HomeroomScopeService.StudentFilter filter) {
        return attemptRepository.findByRoundIdAndDeletedFalse(round.getId()).stream()
                .filter(VocabAttempt::isSubmitted)
                // ★ 재응시를 빼고 대표 건만 센다 — 평균이 반복 응시자 쪽으로 끌린다
                .filter(a -> !a.isRetry())
                .filter(a -> filter.matches(a.getEnrollment().getId()))
                .toList();
    }

    /**
     * 정답률(%).
     *
     * <p>★ 문항 수가 회차마다 다를 수 있어(범위가 작으면 줄어든다) <b>정답 수가 아니라
     * 비율</b>로 평균을 낸다. 정답 수로 평균을 내면 문항이 적은 회차가 점수를 끌어내린다.
     */
    private Integer rateOf(VocabAttempt attempt) {
        if (attempt.getScore() == null || attempt.getTotalCount() == null
                || attempt.getTotalCount() == 0) {
            return null;
        }
        return attempt.getScore() * 100 / attempt.getTotalCount();
    }

    private Integer average(List<Integer> values) {
        // ★ 비어 있으면 null 이다 — 0 으로 두면 「미응시」가 「0점」이 된다
        return values.isEmpty() ? null
                : (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private List<VocabExamRound> roundsIn(Long academyId, Short year,
                                          LocalDate from, LocalDate to) {
        short target = year != null ? year : (short) LocalDate.now().getYear();
        return roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academyId, target)
                .stream()
                .filter(r -> from == null || !r.getOpenDate().isBefore(from))
                .filter(r -> to == null || !r.getOpenDate().isAfter(to))
                .toList();
    }
}
