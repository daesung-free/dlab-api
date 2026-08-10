package com.dlab.domain.qna.repository;

import com.dlab.domain.qna.entity.QnaOfflineReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface QnaOfflineReservationRepository
        extends JpaRepository<QnaOfflineReservation, Long> {

    /** 슬롯의 유효 예약 수. 정원 비교의 기준이라 취소분은 세지 않는다. */
    long countBySlotIdAndCanceledAtIsNullAndDeletedFalse(Long slotId);

    /** 중복 예약 검사. 취소분은 재예약이 가능해야 하므로 제외한다. */
    Optional<QnaOfflineReservation> findBySlotIdAndEnrollmentIdAndCanceledAtIsNullAndDeletedFalse(
            Long slotId, Long enrollmentId);

    /** 슬롯별 예약자 명단 — 관리자 예약 현황. */
    @Query("""
            SELECT r FROM QnaOfflineReservation r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.slot.id IN :slotIds AND r.canceledAt IS NULL AND r.deleted = false
            ORDER BY r.reservedAt
            """)
    List<QnaOfflineReservation> findBySlotIds(@Param("slotIds") List<Long> slotIds);

    /** 학생 본인 예약 — 취소분도 이력으로 함께 나온다. */
    @Query("""
            SELECT r FROM QnaOfflineReservation r
            JOIN FETCH r.slot s
            LEFT JOIN FETCH s.teacher
            WHERE r.enrollment.id = :enrollmentId
              AND s.slotDate >= :from AND s.slotDate <= :to
              AND r.deleted = false
            ORDER BY s.slotDate DESC, s.startTime DESC
            """)
    List<QnaOfflineReservation> findMine(@Param("enrollmentId") Long enrollmentId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);
}
