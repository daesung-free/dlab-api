package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AbsenceReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface AbsenceReasonRepository extends JpaRepository<AbsenceReason, Long> {

    List<AbsenceReason> findByEnrollmentIdAndAttendanceDate(Long enrollmentId, LocalDate attendanceDate);

    /**
     * ★ 승인 요청에 딸린 사유 신청 — 승인 시 벌점을 걸 때 ★ 날짜와 유형을 여기서 얻는다.
     *
     * <p>⚠️ <b>목록이다.</b> 날짜 범위 신청이 여러 행으로 들어올 수 있어, 하나만 집으면
     * 나머지에 벌점이 안 붙는다.
     */
    List<AbsenceReason> findByApprovalRequestId(Long approvalRequestId);

    /**
     * ★ 승인 요청들의 <b>신청자 구분</b>을 한 번에 — 승인 보드가 쓴다.
     *
     * <p>⚠️ 행마다 조회하면 <b>쿼리가 행 수만큼 나간다</b> — 보드는 한 달치를 한 번에
     * 내리므로 수백 건이다.
     */
    @Query("""
            select ar.approvalRequest.id, ar.submitterType
              from AbsenceReason ar
             where ar.approvalRequest.id in :requestIds
               and ar.deleted = false
            """)
    List<Object[]> findSubmitterTypes(@Param("requestIds") Collection<Long> requestIds);

    List<AbsenceReason> findByAcademyIdAndAttendanceDate(Long academyId, LocalDate attendanceDate);

    /**
     * 등록 건의 기간 내 사유신청. 키오스크 {@code getRequestListStd}가 월 단위로 쓴다.
     */
    @Query("""
            SELECT r FROM AbsenceReason r
            WHERE r.enrollment.id = :enrollmentId
              AND r.attendanceDate >= :from
              AND r.attendanceDate <= :to
              AND r.deleted = false
            ORDER BY r.attendanceDate ASC
            """)
    List<AbsenceReason> findByEnrollmentAndPeriod(@Param("enrollmentId") Long enrollmentId,
                                                  @Param("from") LocalDate from,
                                                  @Param("to") LocalDate to);

    /** 지점의 기간 내 사유신청. 관리자 목록이 쓴다. */
    @Query("""
            SELECT r FROM AbsenceReason r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            LEFT JOIN FETCH r.approvalRequest ar
            LEFT JOIN FETCH ar.approvalItem
            WHERE r.academy.id = :academyId
              AND r.attendanceDate >= :from
              AND r.attendanceDate <= :to
              AND r.deleted = false
            ORDER BY r.submittedAt DESC
            """)
    List<AbsenceReason> findByAcademyAndPeriod(@Param("academyId") Long academyId,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);
}
