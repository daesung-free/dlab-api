package com.dlab.domain.report.repository;

import com.dlab.domain.attendance.entity.QAttendanceDailyStatus;
import com.dlab.domain.routine.entity.QDailyRoutineResult;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Daily Report 학생별 집계 원천 (F-4.11-6 목록).
 *
 * <p><b>학생 한 명씩 조회하지 않는다.</b> 화면은 전교생을 순공·재실·루틴 이행 순으로
 * 줄 세우는 곳인데, 학생별 API({@code /learning-plans/.../statistics},
 * {@code /app/daily-reports})를 학생 수만큼 부르면 200명짜리 지점에서 쿼리가 200번이다.
 * 여기서는 <b>지점·기간 조건으로 한 번에</b> 읽어 학생 수와 무관하게 쿼리 수가 고정된다.
 *
 * <p><b>루틴은 DB에서 세고 출결은 행으로 올린다</b> — 비대칭이 의도다. 루틴은 상태별
 * 개수만 필요해 집계로 끝나지만, 출결은 「하원 태깅이 없는 날」을 태깅 원장과 맞춰 봐야
 * 하므로({@code DailyReportBoardService}) 일자별 값이 그대로 있어야 한다. 합계만 받으면
 * 미입력 날의 순공시간이 합계에 섞여 들어가 되돌릴 수 없다.
 */
@Repository
@RequiredArgsConstructor
public class DailyReportBoardRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 확정된 일자 출결 한 줄.
     *
     * <p>{@code studyMinutes}가 {@code null}이면 배치가 계산하지 않은 날이다(결석 등).
     * 값이 있어도 그 날을 쓸 수 있다는 뜻은 아니다 — 하원을 안 찍은 날도 배치가
     * 마지막 교시 종료로 닫아 값을 넣어두기 때문에, 쓸지 말지는 원장을 보고 판단한다.
     */
    public record DailyRow(Long enrollmentId, LocalDate date, Integer studyMinutes) {
    }

    /**
     * 학생별 루틴 이행 집계.
     *
     * <p><b>O/X 2단계다</b>(I-19 확정). 「부분 이행」이 없으므로 중간값을 만들지 말 것.
     *
     * @param done   이행(O) — 제출·검수완료·공개. 학생이 실제로 한 건이다
     * @param missed 미이행(X) — 미제출·결시
     */
    public record RoutineAggregate(long done, long missed) {

        public static final RoutineAggregate EMPTY = new RoutineAggregate(0, 0);

        /**
         * 판정된 건수 — 이행률의 분모다.
         *
         * <p><b>예정·배부 상태는 분모에 넣지 않는다.</b> 그 둘은 아직 결과가 아니라
         * 진행 중인 건이라, 분모에 넣으면 오늘 배부된 시험지 때문에 전교생 이행률이
         * 조회 시각마다 내려간다.
         */
        public long judged() {
            return done + missed;
        }
    }

    /**
     * 지점·기간의 확정 출결. 일자별 행 그대로다.
     *
     * <p>엔티티가 아니라 필요한 세 칸만 올린다 — 지점 전체 한 달이면 학생 × 일수만큼
     * 행이 나오는데, 화면이 쓰는 것은 순공시간 하나뿐이다.
     */
    public List<DailyRow> dailyStatuses(Long academyId, LocalDate from, LocalDate to) {
        QAttendanceDailyStatus daily = QAttendanceDailyStatus.attendanceDailyStatus;

        return queryFactory
                .select(daily.enrollment.id, daily.attendanceDate, daily.studyMinutes)
                .from(daily)
                .where(daily.academy.id.eq(academyId),
                        daily.attendanceDate.between(from, to),
                        daily.deleted.isFalse())
                .fetch().stream()
                .map(row -> new DailyRow(row.get(daily.enrollment.id),
                        row.get(daily.attendanceDate),
                        row.get(daily.studyMinutes)))
                .toList();
    }

    /**
     * 지점·기간의 학생별 루틴 이행. 키는 {@code enrollment_id}다.
     *
     * <p><b>루틴 결과가 하나도 없는 학생은 결과에 없다.</b> 목록에서 빼라는 뜻이 아니라
     * 더할 것이 없다는 뜻이다 — 호출부가 재원생 명단을 기준으로 삼고 여기 없는 학생을
     * {@link RoutineAggregate#EMPTY}로 채운다. 그러지 않으면 아무것도 안 한 학생이
     * 목록에서 사라져, 정작 찾아야 할 대상이 안 보인다.
     */
    public Map<Long, RoutineAggregate> routines(Long academyId, LocalDate from, LocalDate to) {
        QDailyRoutineResult result = QDailyRoutineResult.dailyRoutineResult;

        List<Tuple> rows = queryFactory
                .select(result.enrollment.id, result.status, result.count())
                .from(result)
                .where(result.academy.id.eq(academyId),
                        result.resultDate.between(from, to),
                        result.deleted.isFalse())
                .groupBy(result.enrollment.id, result.status)
                .fetch();

        Map<Long, long[]> counts = new HashMap<>();
        for (Tuple row : rows) {
            RoutineResultStatus status = row.get(result.status);
            long count = row.get(result.count()) == null ? 0L : row.get(result.count());
            long[] slot = counts.computeIfAbsent(row.get(result.enrollment.id),
                    k -> new long[2]);
            if (isDone(status)) {
                slot[0] += count;
            } else if (isMissed(status)) {
                slot[1] += count;
            }
            // 예정·배부는 어느 쪽도 아니다 — 아직 결과가 나오지 않은 건이다
        }

        Map<Long, RoutineAggregate> aggregates = new HashMap<>();
        counts.forEach((enrollmentId, slot) ->
                aggregates.put(enrollmentId, new RoutineAggregate(slot[0], slot[1])));
        return aggregates;
    }

    /**
     * 이행(O)으로 보는 상태.
     *
     * <p>검수 전({@code SUBMITTED})도 이행이다 — 이행 여부는 <b>학생이 했는가</b>이고,
     * 점수가 확정됐는지는 다른 축이다. 공개된 것만 세면 교사가 검수를 미룬 만큼
     * 학생의 이행률이 떨어진다.
     */
    private static boolean isDone(RoutineResultStatus status) {
        return status == RoutineResultStatus.SUBMITTED
                || status == RoutineResultStatus.REVIEWED
                || status == RoutineResultStatus.PUBLISHED;
    }

    private static boolean isMissed(RoutineResultStatus status) {
        return status == RoutineResultStatus.NOT_SUBMITTED
                || status == RoutineResultStatus.ABSENT;
    }
}
