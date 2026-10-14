package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionDuplicateDecision;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdmissionDuplicateDecisionRepository
        extends JpaRepository<AdmissionDuplicateDecision, Long> {

    /**
     * 이 예약에 대해 「다른 사람」으로 정리된 등록 건 id 들.
     *
     * <p>★ <b>id 만 뽑는다.</b> 탐지는 후보를 거르는 데만 쓰므로 엔티티를 통째로 올릴
     * 이유가 없고, 지연로딩이 섞이면 목록 조회에서 N+1 이 난다.
     */
    @Query("""
            SELECT d.enrollment.id FROM AdmissionDuplicateDecision d
            WHERE d.reservation.id = :reservationId AND d.deleted = false
            """)
    List<Long> decidedEnrollmentIds(@Param("reservationId") Long reservationId);

    /**
     * 이 등록 건이 「다른 사람」으로 정리한 예약 id 들.
     *
     * <p>목록(`/duplicates`)이 반대 방향으로 거를 때 쓴다.
     */
    @Query("""
            SELECT d.reservation.id FROM AdmissionDuplicateDecision d
            WHERE d.enrollment.id IN :enrollmentIds AND d.deleted = false
            """)
    List<Long> decidedReservationIds(@Param("enrollmentIds") List<Long> enrollmentIds);

    boolean existsByReservationIdAndEnrollmentIdAndDeletedFalse(Long reservationId,
                                                               Long enrollmentId);
}
