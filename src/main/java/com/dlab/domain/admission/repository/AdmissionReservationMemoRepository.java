package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionReservationMemo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdmissionReservationMemoRepository
        extends JpaRepository<AdmissionReservationMemo, Long> {

    @Query("""
            SELECT m FROM AdmissionReservationMemo m
            WHERE m.reservation.id = :reservationId AND m.deleted = false
            ORDER BY m.createdAt DESC, m.id DESC
            """)
    List<AdmissionReservationMemo> findByReservationId(Long reservationId);
}
