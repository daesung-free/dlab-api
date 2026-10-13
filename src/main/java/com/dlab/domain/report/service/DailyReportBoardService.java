package com.dlab.domain.report.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.attendance.service.StudyTimeCalculator;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository;
import com.dlab.domain.plan.repository.LearningPlanBoardRepository.ClassRef;
import com.dlab.domain.report.repository.DailyReportBoardRepository;
import com.dlab.domain.report.repository.DailyReportBoardRepository.DailyRow;
import com.dlab.domain.report.repository.DailyReportBoardRepository.RoutineAggregate;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.HomeroomScopeService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily Report 학생별 집계·순위 (F-4.11-6 목록).
 *
 * <h2>왜 기존 두 API로는 안 되는가</h2>
 * 서버에 있던 것은 <b>지점 전체 개요</b>({@code /statistics})와 <b>학생 1명</b>
 * ({@code /app/daily-reports}, {@code /learning-plans/students/&#123;id&#125;/statistics})
 * 둘뿐이다. 그런데 화면이 원하는 것은 <b>전교생을 줄 세우는 것</b>이라, 학생별 API를
 * 학생 수만큼 부르게 된다 — 200명짜리 지점이면 쿼리가 200번이고, 정렬은 전부 받아온
 * 뒤에야 할 수 있어 페이지를 나눌 수도 없다.
 *
 * <p>그래서 <b>쿼리 수가 학생 수와 무관하게 고정</b>된다 — 재원생 명단 · 반 배정 ·
 * 태깅 원장 · 확정 출결 · 루틴 집계 · 요일별 교시(최대 3)로, 학생이 몇 명이어도 늘지 않는다
 * ({@code AttendanceBoardService}와 같은 방식).
 *
 * <h2>★★ 하원 태깅이 없는 날은 「미입력」이다 — 0이 아니다 (2026-10-13 확정)</h2>
 * 그런 날의 순공시간은 <b>직원이 수기 입력</b>한다({@code ManualCheckOutService}).
 * 들어오기 전까지 서버는 계산하지 않는다. ⚠️ <b>확정 배치가 그 날에도 숫자를 넣어둔다</b> —
 * 마지막 교시 종료로 닫아버리기 때문이다. 그걸 그대로 합치면 보정 전후로 값이 달라지고,
 * 0으로 채우면 <b>진짜 0분과 구분되지 않아</b> 담당자가 그 학생을 「공부 안 한 학생」으로
 * 읽는다. 그래서 그 날은 합계에서 빼고 {@link BoardRow#missingCheckOutDays}로 따로 센다.
 *
 * <h2>★ 집중도는 내리지 않는다</h2>
 * {@link BoardRow#concentration}이 항상 {@code null}이다 — 코드 어디에도 정의가 없고
 * 요구사항에도 산식이 없다. 추측해서 숫자를 채우면 <b>화면이 그걸 사실로 보여준다.</b>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyReportBoardService {

    /**
     * 조회 범위 상한.
     *
     * <p>태깅 원장을 기간 통째로 읽는다 — 재실시간과 「하원 미입력」을 원장 없이는
     * 가를 수 없다. 지점 300명 × 한 달이면 태깅이 수만 건이라 이 이상 열면 조용히 느려진다
     * ({@code AttendanceBoardService}와 같은 상한).
     */
    private static final int MAX_RANGE_DAYS = 31;

    private final StudentEnrollmentRepository enrollmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final PeriodMasterRepository periodMasterRepository;
    private final DailyReportBoardRepository boardRepository;
    // 반·담임은 학습계획 현황과 <b>같은 질문</b>이라 조회를 재사용한다 — 두 벌 두면
    // 담임 예외 지정 같은 규칙이 한쪽만 고쳐져 화면마다 담임이 달라진다
    private final LearningPlanBoardRepository classRepository;
    private final HomeroomScopeService homeroomScopeService;
    private final StudyTimeCalculator studyTimeCalculator;
    private final Clock clock;

    /**
     * 목록 한 줄.
     *
     * @param studentName  상위 관리자가 아니면 가려져 있다({@code masked})
     * @param masked       이름이 가려졌는지 — 없으면 화면이 "이름이 잘못 저장됐다"로 오인한다
     * @param studyMinutes 순공시간(재실 − 외출 − 급식 − 쉬는시간) 합계.
     *                     <b>집계할 날이 하나도 없으면 {@code null}</b>이다 — 0이 아니다
     * @param presenceMinutes 재실시간 합계. 순공과 <b>같은 재실 구간</b>에서 나온다
     * @param concentration ⚠️ <b>정의 미확정이라 항상 {@code null}이다.</b> 산식이 코드에도
     *                     요구사항에도 없다. 추측한 숫자를 내리면 화면이 사실로 보여준다
     * @param attendedDays 등원 태깅이 있던 날 수
     * @param measuredDays 순공·재실을 집계한 날 수 — 화면이 "N일 기준"의 분모로 쓴다
     * @param missingCheckOutDays <b>하원 태깅이 없어 집계하지 않은 날</b>. 직원 수기 입력
     *                     대상이다. 0분이 아니라 <b>미입력</b>이라는 뜻이라 따로 센다
     * @param inProgressDays 아직 학원에 있는 날(오늘). 잠정값으로 집계에 들어간다 —
     *                     미입력으로 세면 오늘 조회 시 전교생이 미입력으로 보인다
     * @param routineDone   루틴 이행(O) 건수. <b>O/X 2단계다</b>(I-19) — 부분 이행이 없다
     * @param routineMissed 루틴 미이행(X) 건수(미제출·결시)
     * @param routineJudged 판정된 건수(= O + X). 예정·배부는 들어가지 않는다
     * @param routineCompletionRate 이행률(%). 판정된 건이 없으면 {@code null}이다 —
     *                     "루틴이 없던 기간"과 "0% 이행"은 다르다
     * @param studyRank     순공시간 등수. <b>지점·기간 전체 기준</b>이고 페이지 안 순서가 아니다.
     *                     집계값이 없으면 {@code null} — 꼴찌로 내리면 미입력이 성적이 된다
     */
    public record BoardRow(
            Long enrollmentId,
            String studentNo,
            String studentName,
            boolean masked,
            Long classId,
            String className,
            Long homeroomTeacherId,
            String homeroomTeacherName,
            Integer studyMinutes,
            Integer presenceMinutes,
            Integer concentration,
            long attendedDays,
            long measuredDays,
            long missingCheckOutDays,
            long inProgressDays,
            long routineDone,
            long routineMissed,
            long routineJudged,
            Integer routineCompletionRate,
            Integer studyRank,
            Integer presenceRank,
            Integer routineRank) {
    }

    /**
     * 정렬 허용 목록. 여기 없는 키는 무시한다 — 클라이언트가 보낸 문자열을 그대로 정렬
     * 키로 쓰면 없는 필드에서 예외가 나거나 의도치 않은 값으로 정렬된다(CLAUDE.md §7).
     *
     * <p>정렬 키가 <b>DB 컬럼이 아니라 파생값</b>이라 {@code SearchSupport.toOrders()}를
     * 쓸 수 없다. 허용 목록으로 임의 정렬을 막는 규칙은 지키되 비교자로 구현한다
     * ({@code LearningPlanBoardService}와 같은 방식).
     *
     * <p><b>집계값이 없는 학생은 어느 정렬에서도 뒤로 간다</b>({@code nullsLast}).
     * 미입력을 0으로 취급해 섞으면 "순공 적은 순" 맨 앞이 미입력 학생으로 채워져,
     * 정작 손이 필요한 학생이 그 아래에 묻힌다.
     */
    private static final Map<String, SortOption> SORTABLE = Map.of(
            "studentNo", option(BoardRow::studentNo),
            "studentName", option(BoardRow::studentName),
            "className", option(BoardRow::className),
            "studyMinutes", option(BoardRow::studyMinutes),
            "presenceMinutes", option(BoardRow::presenceMinutes),
            "routineCompletionRate", option(BoardRow::routineCompletionRate),
            "missingCheckOutDays", option(BoardRow::missingCheckOutDays));

    /**
     * 정렬 한 키. 오름·내림을 <b>한 쌍으로 묶어 둔다</b>.
     *
     * <p>내림차순을 {@code reversed()}로 만들면 {@code nullsLast}가 같이 뒤집혀
     * <b>집계값이 없는 학생이 맨 위로 올라온다</b> — 미입력이 1등 자리에 놓인다.
     * 그래서 방향별 비교자를 따로 만들고 양쪽 모두 {@code null}을 뒤로 보낸다.
     */
    private record SortOption(Comparator<BoardRow> ascending, Comparator<BoardRow> descending) {

        Comparator<BoardRow> of(boolean desc) {
            return desc ? descending : ascending;
        }
    }

    private static <T extends Comparable<T>> SortOption option(
            java.util.function.Function<BoardRow, T> value) {
        return new SortOption(
                Comparator.comparing(value, Comparator.nullsLast(Comparator.naturalOrder())),
                Comparator.comparing(value, Comparator.nullsLast(Comparator.reverseOrder())));
    }

    /**
     * 지점·기간의 학생별 집계 + 순위.
     *
     * @param academyId 지점. 전 지점 권한자만 지정할 수 있고 지점 관리자는 자기 지점으로
     *                  강제된다 — 요청 값을 그대로 믿으면 남의 지점이 새어나간다(CLAUDE.md §7)
     * @param classId   반 필터. 비우면 지점 전체(반 미배정 포함). <b>담임은 이 값과 무관하게
     *                  맡은 학생으로 좁혀진다</b>
     */
    public Page<BoardRow> board(AuthPrincipal me, Long academyId, LocalDate from, LocalDate to,
                                Long classId, Pageable pageable) {
        Long scope = me.requireAcademyScope(academyId);
        verifyRange(from, to);

        List<StudentEnrollment> targets = enrollmentRepository.findCurrentByAcademyId(scope).stream()
                // 휴원·퇴원은 공부하러 오지 않는 상태다. 목록에 남기면 전원이 미집계로 잡혀
                // "손이 필요한 학생" 목록이 퇴원생으로 채워진다
                .filter(e -> e.getEnrollmentStatus() == EnrollmentStatus.ENROLLED)
                .toList();
        if (targets.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        short year = targets.get(0).getYear();

        Map<Long, Map<LocalDate, List<AttendanceTaggingLog>>> logs = logsOf(scope, from, to);
        Map<Long, Map<LocalDate, Integer>> confirmed = confirmedOf(scope, from, to);
        Map<DayType, List<PeriodMaster>> periods = periodsOf(scope, year, from, to);
        Map<Long, RoutineAggregate> routines = boardRepository.routines(scope, from, to);
        Map<Long, ClassRef> classes = classRepository.fixedClasses(scope);

        // ★ 순위는 반 필터·담임 범위를 걸기 <b>전에</b> 매긴다. 거른 뒤에 매기면 같은 학생의
        //   등수가 "1반만 조회"했을 때와 "전체 조회"했을 때 달라져, 담임과 본사가 서로 다른
        //   숫자를 보고 통화하게 된다. 등수의 모집단은 언제나 그 지점·그 기간의 재원생이다
        List<Metric> metrics = targets.stream()
                .map(e -> measure(e, from, to,
                        logs.getOrDefault(e.getId(), Map.of()),
                        confirmed.getOrDefault(e.getId(), Map.of()),
                        periods,
                        routines.getOrDefault(e.getId(), RoutineAggregate.EMPTY)))
                .toList();

        Map<Long, Integer> studyRanks = rank(metrics, Metric::studyMinutes);
        Map<Long, Integer> presenceRanks = rank(metrics, Metric::presenceMinutes);
        Map<Long, Integer> routineRanks = rank(metrics, Metric::routineCompletionRate);

        // ★ 담임은 맡은 학생만 본다. classId는 화면이 고르는 필터라 빼고 부르면 지점 전체가
        //   나간다. 그리고 <b>페이징 뒤에 걸러내면 전체 건수가 어긋난다</b> — 20명 중 3명을
        //   지운 페이지가 "총 20건"으로 내려가 화면이 빈 페이지를 계속 넘긴다
        var studentFilter = homeroomScopeService.resolveStudentFilter(me, year, classId);
        if (studentFilter.blocksEverything()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        List<BoardRow> rows = metrics.stream()
                .filter(m -> studentFilter.matches(m.enrollment().getId()))
                .filter(m -> matchesClass(classes.get(m.enrollment().getId()), classId))
                .map(m -> toRow(me, m, classes.get(m.enrollment().getId()),
                        studyRanks.get(m.enrollment().getId()),
                        presenceRanks.get(m.enrollment().getId()),
                        routineRanks.get(m.enrollment().getId())))
                .sorted(comparator(pageable.getSort()))
                .toList();

        return page(rows, pageable);
    }

    // ── 집계 ──────────────────────────────────────────────────

    /** 한 학생의 기간 집계. 등수를 매기기 전 단계라 원시 숫자만 들고 있다. */
    private record Metric(StudentEnrollment enrollment,
                          Integer studyMinutes, Integer presenceMinutes,
                          long attendedDays, long measuredDays,
                          long missingCheckOutDays, long inProgressDays,
                          RoutineAggregate routine, Integer routineCompletionRate) {
    }

    /**
     * 하루씩 걸어가며 더한다.
     *
     * <p><b>날짜마다 쓸 수 있는 날인지 먼저 가른다</b> — 합계부터 받으면 하원 미입력 날이
     * 섞여 들어가고, 그건 나중에 되돌릴 수 없다.
     */
    private Metric measure(StudentEnrollment enrollment, LocalDate from, LocalDate to,
                           Map<LocalDate, List<AttendanceTaggingLog>> logsByDate,
                           Map<LocalDate, Integer> confirmedByDate,
                           Map<DayType, List<PeriodMaster>> periods,
                           RoutineAggregate routine) {
        LocalDate today = LocalDate.now(clock);
        int study = 0;
        int presence = 0;
        long attended = 0;
        long measured = 0;
        long missingCheckOut = 0;
        long inProgress = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<AttendanceTaggingLog> dayLogs = logsByDate.get(date);
            if (dayLogs == null || dayLogs.stream().noneMatch(l -> l.getEventType().isArrival())) {
                // 등원이 없는 날 — 결석이거나 아직 오지 않은 날이다. 어느 쪽이든 공부한
                // 시간이 없는 것이 아니라 <b>셀 대상이 아니다</b>
                continue;
            }
            attended++;

            boolean departed = dayLogs.stream().anyMatch(DailyReportBoardService::isDeparture);
            if (!departed && date.isBefore(today)) {
                // ★ 지난 날인데 하원이 없다 — 직원 수기 입력 대상이다. 확정 배치가 넣어둔
                //   숫자가 있어도 쓰지 않는다. 그 값은 마지막 교시 종료로 닫은 것이라
                //   보정이 들어오면 달라지고, 합계에 섞으면 미입력이 숫자로 위장된다
                missingCheckOut++;
                continue;
            }
            if (!departed) {
                // 오늘 아직 학원에 있다. 잠정값으로 더한다 — 미입력으로 세면
                // 오늘 화면을 열었을 때 전교생이 미입력으로 보인다
                inProgress++;
            }

            List<PeriodMaster> dayPeriods = periods.getOrDefault(DayType.of(date), List.of());
            LocalTime until = date.equals(today) ? LocalTime.now(clock) : LocalTime.MAX;

            // 확정값이 있으면 그걸 쓴다 — 교시를 나중에 고쳐도 그날 기준이 유지된다.
            // 매번 다시 계산하면 과거 순공시간이 소급해서 바뀐다
            Integer stored = confirmedByDate.get(date);
            study += stored != null ? stored
                    : (int) studyTimeCalculator.calculate(dayLogs, dayPeriods, until).toMinutes();
            // 재실은 저장해둔 칸이 없어 매번 계산한다. 순공과 <b>같은 재실 구간</b>에서
            // 나오므로 외출 처리가 양쪽에서 갈리지 않는다 — 호출부가 따로 세면 갈린다.
            // ⚠️ 순공만 저장값을 쓰므로, 교시를 고친 뒤에는 그날 두 값의 기준 시점이
            //    다를 수 있다. 과거 순공을 소급해서 바꾸지 않는 쪽을 택한 결과다
            presence += (int) studyTimeCalculator.presence(dayLogs, dayPeriods, until).toMinutes();
            measured++;
        }

        return new Metric(enrollment,
                // ★ 집계할 날이 없으면 0이 아니라 null 이다. 0으로 두면 하루도 안 온 학생과
                //   와서 공부를 안 한 학생이 같은 값이 된다
                measured == 0 ? null : study,
                measured == 0 ? null : presence,
                attended, measured, missingCheckOut, inProgress,
                routine, completionRate(routine));
    }

    /**
     * 루틴 이행률.
     *
     * <p><b>판정된 건이 없으면 {@code null}</b>이다. 0%로 두면 루틴 세팅이 없던 기간을
     * 조회했을 때 전교생이 0%로 보이고, 100%로 두면 아무것도 안 한 학생이 목록 맨 위에
     * 우등생으로 뜬다.
     */
    private static Integer completionRate(RoutineAggregate routine) {
        if (routine.judged() == 0) {
            return null;
        }
        return (int) Math.round(routine.done() * 100.0 / routine.judged());
    }

    /**
     * 지표별 등수.
     *
     * <h2>★ 순위는 서버가 센다</h2>
     * 화면이 세면 <b>페이징된 그 페이지 안에서만</b> 등수가 매겨져 2페이지 첫 줄이 또 1등이 된다.
     *
     * <h2>★★ 동점은 같은 등수이고 다음은 건너뛴다 (1, 1, 3)</h2>
     * <b>기존 순공 랭킹({@code StudyTimeRankingService})과 같은 규칙</b>이어야 한다 —
     * 학생이 앱에서 본 등수와 관리자 화면 등수가 갈리면 어느 쪽이 맞느냐는 문의가 된다.
     * 그리고 분 단위 합계라 동점이 실제로 나오는데, 임의로 순서를 매기면 같은 시간을
     * 공부한 두 학생에게 다른 등수가 보인다. 촘촘한 등수(1,1,2)로 하지 않는 이유는
     * "N명 중 3등"의 분모와 등수가 어긋나기 때문이다.
     *
     * <p><b>집계값이 없는 학생은 등수가 없다</b>({@code null}). 꼴찌로 내리면 미입력이
     * 성적처럼 보이고, 1등으로 올리면 더 나쁘다.
     */
    private static <T extends Comparable<T>> Map<Long, Integer> rank(
            List<Metric> metrics, java.util.function.Function<Metric, T> value) {

        List<Metric> ranked = metrics.stream()
                .filter(m -> value.apply(m) != null)
                .sorted(Comparator.comparing(value, Comparator.<T>reverseOrder()))
                .toList();

        Map<Long, Integer> result = new HashMap<>();
        T previous = null;
        int rank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            T current = value.apply(ranked.get(i));
            if (previous == null || current.compareTo(previous) != 0) {
                rank = i + 1;               // 동점 다음은 건너뛴 등수(1,1,3)
                previous = current;
            }
            result.put(ranked.get(i).enrollment().getId(), rank);
        }
        return result;
    }

    // ── 묶음 조회 ─────────────────────────────────────────────

    /**
     * 학생 × 일자 태깅 원장.
     *
     * <p><b>학생마다 조회하지 않는다</b> — 지점·기간으로 한 번 읽어 메모리에서 묶는다
     * ({@code AttendanceBoardService}와 같은 방식). 원장이 필요한 이유는 둘이다:
     * 재실시간을 계산해야 하고, <b>하원 태깅이 없는 날을 가려내야</b> 한다.
     */
    private Map<Long, Map<LocalDate, List<AttendanceTaggingLog>>> logsOf(
            Long academyId, LocalDate from, LocalDate to) {

        Map<Long, Map<LocalDate, List<AttendanceTaggingLog>>> result = new HashMap<>();
        for (AttendanceTaggingLog log : taggingLogRepository
                .findByAcademyAndPeriod(academyId, from, to)) {
            result.computeIfAbsent(log.getEnrollment().getId(), k -> new HashMap<>())
                    .computeIfAbsent(log.getAttendanceDate(), k -> new ArrayList<>())
                    .add(log);
        }
        return result;
    }

    /** 학생 × 일자 확정 순공시간. 값이 없는 날(결석 등)은 담지 않는다. */
    private Map<Long, Map<LocalDate, Integer>> confirmedOf(Long academyId,
                                                           LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, Integer>> result = new HashMap<>();
        for (DailyRow row : boardRepository.dailyStatuses(academyId, from, to)) {
            if (row.studyMinutes() == null) {
                continue;
            }
            result.computeIfAbsent(row.enrollmentId(), k -> new HashMap<>())
                    .put(row.date(), row.studyMinutes());
        }
        return result;
    }

    /**
     * 요일 구분별 교시.
     *
     * <p>기간에 들어 있는 구분만 읽으므로 <b>최대 3번</b>이다(평일·토·일). 날짜마다 읽으면
     * 한 달 조회에 31번이 나가는데 교시는 요일 구분으로만 갈린다.
     */
    private Map<DayType, List<PeriodMaster>> periodsOf(Long academyId, short year,
                                                       LocalDate from, LocalDate to) {
        Map<DayType, List<PeriodMaster>> result = new EnumMap<>(DayType.class);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            result.computeIfAbsent(DayType.of(date),
                    dayType -> periodMasterRepository.findByDayType(academyId, year, dayType));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────

    /** 하원·조퇴 중 하나라도 있으면 그날은 닫혔다. <b>외출은 하원이 아니다</b>. */
    private static boolean isDeparture(AttendanceTaggingLog log) {
        return log.getEventType() == AttendanceEventType.CHECK_OUT
                || log.getEventType() == AttendanceEventType.EARLY_LEAVE;
    }

    private BoardRow toRow(AuthPrincipal me, Metric metric, ClassRef clazz,
                           Integer studyRank, Integer presenceRank, Integer routineRank) {
        StudentEnrollment enrollment = metric.enrollment();
        boolean raw = PersonalDataPolicy.canViewRaw(me);
        String name = enrollment.getStudent().getName();

        return new BoardRow(
                enrollment.getId(),
                enrollment.getStudentNo(),
                raw ? name : Masking.name(name),
                !raw,
                clazz == null ? null : clazz.id(),
                clazz == null ? null : clazz.name(),
                homeroomIdOf(enrollment, clazz),
                homeroomNameOf(enrollment, clazz),
                metric.studyMinutes(),
                metric.presenceMinutes(),
                // ⚠️ 집중도 — <b>정의 미확정</b>이라 비워 둔다. 산식이 코드에도 요구사항에도
                //    없어서, 추측해 채우면 화면이 그 숫자를 사실로 보여준다.
                //    정해지면 여기서 계산한다(필드·응답 모양은 그대로 쓸 수 있다)
                null,
                metric.attendedDays(),
                metric.measuredDays(),
                metric.missingCheckOutDays(),
                metric.inProgressDays(),
                metric.routine().done(),
                metric.routine().missed(),
                metric.routine().judged(),
                metric.routineCompletionRate(),
                studyRank, presenceRank, routineRank);
    }

    /**
     * 담임 = 예외 지정 ?? 반 담임({@code HomeroomResolver}와 같은 해석).
     *
     * <p>반 담임은 반 조회에 이미 실려 오므로 예외 지정된 학생만 따로 본다 — 드문 경우라
     * 학생마다 쿼리가 나가지 않는다.
     */
    private static Long homeroomIdOf(StudentEnrollment enrollment, ClassRef clazz) {
        if (enrollment.getHomeroomOverride() != null) {
            return enrollment.getHomeroomOverride().getId();
        }
        return clazz == null ? null : clazz.homeroomTeacherId();
    }

    private static String homeroomNameOf(StudentEnrollment enrollment, ClassRef clazz) {
        if (enrollment.getHomeroomOverride() != null) {
            return enrollment.getHomeroomOverride().getName();
        }
        return clazz == null ? null : clazz.homeroomTeacherName();
    }

    private static boolean matchesClass(ClassRef clazz, Long classId) {
        if (classId == null) {
            return true;
        }
        return clazz != null && clazz.id().equals(classId);
    }

    /**
     * 정렬 지정이 없으면 <b>순공시간 많은 순</b>이다 — 이 화면은 줄 세우는 곳이고,
     * 등수 1등이 첫 줄에 오는 것이 화면과 응답이 어긋나지 않는 유일한 기본값이다.
     *
     * <p>마지막에 학번을 항상 덧붙인다. 안 그러면 값이 같은 학생들의 순서가 조회마다
     * 달라져 페이지를 넘길 때 같은 학생이 두 번 나오거나 빠진다.
     */
    private Comparator<BoardRow> comparator(Sort sort) {
        Comparator<BoardRow> result = null;
        for (Sort.Order order : sort) {
            SortOption option = SORTABLE.get(order.getProperty());
            if (option == null) {
                continue;
            }
            Comparator<BoardRow> next = option.of(order.isDescending());
            result = result == null ? next : result.thenComparing(next);
        }
        if (result == null) {
            result = SORTABLE.get("studyMinutes").descending();
        }
        return result.thenComparing(SORTABLE.get("studentNo").ascending());
    }

    private Page<BoardRow> page(List<BoardRow> rows, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(rows, pageable, rows.size());
        }
        int fromIndex = (int) Math.min(pageable.getOffset(), rows.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), rows.size());
        return new PageImpl<>(rows.subList(fromIndex, toIndex), pageable, rows.size());
    }

    private void verifyRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 종료일이 시작일보다 빠릅니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "조회 기간은 최대 %d일입니다.".formatted(MAX_RANGE_DAYS));
        }
    }
}
