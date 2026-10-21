package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AttendanceTaggingLogRepository extends JpaRepository<AttendanceTaggingLog, Long> {

    /** 이 등록 건에 태깅 이력이 하나라도 있는가 — 학생 삭제를 막는 근거다. */
    boolean existsByEnrollmentIdAndDeletedFalse(Long enrollmentId);

    /**
     * 그 학생 그날의 원장.
     *
     * <h2>★★ 파생 쿼리가 아니라 {@code @Query} 인 이유 — 삭제된 태깅을 빼야 한다</h2>
     * 메서드 이름만으로 만들면 {@code is_deleted} 를 보지 않아, <b>정정으로 지운 태깅이
     * 순공시간·일자 확정에 계속 들어간다.</b> 지우는 화면은 있는데 숫자가 안 바뀌는
     * 상태가 되고, 원인이 보이지 않는다.
     *
     * <p>이름을 {@code ...AndDeletedFalse...} 로 바꾸면 호출부 5곳을 고쳐야 해서
     * 이름을 유지한 채 조건만 넣었다.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM AttendanceTaggingLog l
            WHERE l.enrollment.id = :enrollmentId
              AND l.attendanceDate = :attendanceDate
              AND l.deleted = false
            ORDER BY l.recordedAt ASC
            """)
    List<AttendanceTaggingLog> findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(
            Long enrollmentId, LocalDate attendanceDate);

    /** 지점 그날의 원장. ★ 위와 같은 이유로 {@code @Query} 다 — 삭제분을 뺀다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM AttendanceTaggingLog l
            WHERE l.academy.id = :academyId
              AND l.attendanceDate = :attendanceDate
              AND l.deleted = false
            """)
    List<AttendanceTaggingLog> findByAcademyIdAndAttendanceDate(Long academyId, LocalDate attendanceDate);

    /** 지점의 기간 내 원장 전체. 통계·순공시간 집계가 쓴다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM AttendanceTaggingLog l
            JOIN FETCH l.enrollment e
            JOIN FETCH e.student
            WHERE l.academy.id = :academyId
              AND l.attendanceDate >= :from
              AND l.attendanceDate <= :to
              AND l.deleted = false
            ORDER BY l.recordedAt ASC
            """)
    List<AttendanceTaggingLog> findByAcademyAndPeriod(Long academyId, LocalDate from, LocalDate to);

    /** 등록 건의 기간 내 원장. 학생별 출결 특이사항(3.26)·지각 수(3.15)가 쓴다. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT l FROM AttendanceTaggingLog l
            WHERE l.enrollment.id = :enrollmentId
              AND l.attendanceDate >= :from
              AND l.attendanceDate <= :to
              AND l.deleted = false
            ORDER BY l.recordedAt ASC
            """)
    List<AttendanceTaggingLog> findByEnrollmentAndPeriod(Long enrollmentId, LocalDate from, LocalDate to);
}
