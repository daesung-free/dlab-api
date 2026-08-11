package com.dlab.domain.report.repository;

import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 랭킹 적재용 원천 조회.
 *
 * <p>배치가 <b>한 번 읽고 메모리에서 전체·지점 랭킹을 둘 다 만든다</b> — 지점 수만큼
 * 쿼리를 나누면 같은 행을 아홉 번 읽는다.
 */
public interface DailyStudyRepository extends JpaRepository<AttendanceDailyStatus, Long> {

    /**
     * 기간 내 학생별 순공시간 합계.
     *
     * <p>{@code studyMinutes}가 없는 행(결석·미확정)은 SUM에서 빠지고,
     * 전부 없는 학생은 {@code HAVING}으로 제외된다 — <b>0분으로 채우면 랭킹 꼬리가
     * 재원생 명단이 된다.</b>
     *
     * @return {@code [enrollmentId, academyId, year, studyMinutes]}
     */
    @Query("""
            SELECT d.enrollment.id, d.academy.id, d.enrollment.year, SUM(d.studyMinutes)
            FROM AttendanceDailyStatus d
            WHERE d.attendanceDate >= :from AND d.attendanceDate <= :to
              AND d.deleted = false
              AND d.enrollment.current = true
              AND d.enrollment.deleted = false
            GROUP BY d.enrollment.id, d.academy.id, d.enrollment.year
            HAVING SUM(d.studyMinutes) IS NOT NULL
            """)
    List<Object[]> sumByEnrollment(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
