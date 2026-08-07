package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceDailyStatusRepository extends JpaRepository<AttendanceDailyStatus, Long> {

    Optional<AttendanceDailyStatus> findByEnrollmentIdAndAttendanceDate(Long enrollmentId, LocalDate attendanceDate);

    /**
     * 지점의 기간 내 결석 수. 키오스크 {@code getAttendState}가 쓴다.
     *
     * <p><b>사유·무단을 합쳐 센다</b> — 규격서·키오스크가 {@code absence_cnt}를 그냥
     * "결석 횟수"로 정의하고 필드도 하나뿐이라, 무단만 세면 사유결석한 학생이
     * 화면에서 결석 0회로 보인다.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(d) FROM AttendanceDailyStatus d
            WHERE d.academy.id = :academyId
              AND d.attendanceDate >= :from
              AND d.attendanceDate <= :to
              AND d.finalStatus = com.dlab.domain.attendance.entity.DailyStatus.ABSENT
              AND d.deleted = false
            """)
    long countAbsenceByAcademy(Long academyId, LocalDate from, LocalDate to);

    /** 등록 건의 기간 내 결석 수. 키오스크 {@code getStdAttendState}가 쓴다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(d) FROM AttendanceDailyStatus d
            WHERE d.enrollment.id = :enrollmentId
              AND d.attendanceDate >= :from
              AND d.attendanceDate <= :to
              AND d.finalStatus = com.dlab.domain.attendance.entity.DailyStatus.ABSENT
              AND d.deleted = false
            """)
    long countAbsenceByEnrollment(Long enrollmentId, LocalDate from, LocalDate to);

    /**
     * 등록 건의 기간 내 확정 행. 앱 출결 조회(A-18)가 쓴다.
     *
     * <p>확정 배치는 <b>교시가 있는 날</b>만 돌므로 주말·공휴일에는 행이 없을 수 있다 —
     * 호출자가 태깅 원장과 합쳐서 봐야 달력에 구멍이 안 생긴다.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d FROM AttendanceDailyStatus d
            WHERE d.enrollment.id = :enrollmentId
              AND d.attendanceDate >= :from
              AND d.attendanceDate <= :to
              AND d.deleted = false
            ORDER BY d.attendanceDate
            """)
    List<AttendanceDailyStatus> findByEnrollmentAndPeriod(Long enrollmentId, LocalDate from, LocalDate to);

    /** 지점의 그날 확정 행 전체. 순공시간 재계산이 쓴다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT d FROM AttendanceDailyStatus d
            JOIN FETCH d.enrollment
            WHERE d.academy.id = :academyId
              AND d.attendanceDate = :date
              AND d.deleted = false
            """)
    List<AttendanceDailyStatus> findByAcademyIdAndAttendanceDate(Long academyId, LocalDate date);
}
