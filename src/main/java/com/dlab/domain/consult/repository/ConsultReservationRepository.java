package com.dlab.domain.consult.repository;

import com.dlab.domain.consult.entity.ConsultReservation;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsultReservationRepository extends JpaRepository<ConsultReservation, Long> {

    long countBySlotIdAndCanceledAtIsNullAndDeletedFalse(Long slotId);

    Optional<ConsultReservation> findBySlotIdAndEnrollmentIdAndCanceledAtIsNullAndDeletedFalse(
            Long slotId, Long enrollmentId);

    /** 슬롯별 예약자 명단 — 담임 화면용. 학생 화면에는 인원 수만 내린다. */
    @Query("""
            SELECT r FROM ConsultReservation r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.slot.id IN :slotIds AND r.canceledAt IS NULL AND r.deleted = false
            ORDER BY r.reservedAt
            """)
    List<ConsultReservation> findBySlotIds(@Param("slotIds") List<Long> slotIds);

    /** 학생 본인 예약 내역. 취소분도 이력으로 나온다. */
    @Query("""
            SELECT r FROM ConsultReservation r
            JOIN FETCH r.slot s
            JOIN FETCH s.teacher
            WHERE r.enrollment.id = :enrollmentId
              AND s.slotDate BETWEEN :from AND :to
              AND r.deleted = false
            ORDER BY s.slotDate DESC, s.startTime DESC
            """)
    List<ConsultReservation> findMine(@Param("enrollmentId") Long enrollmentId,
                                      @Param("from") LocalDate from,
                                      @Param("to") LocalDate to);
}
