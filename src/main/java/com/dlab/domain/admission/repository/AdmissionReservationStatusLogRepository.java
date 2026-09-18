package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionReservationStatusLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AdmissionReservationStatusLogRepository
        extends JpaRepository<AdmissionReservationStatusLog, Long> {

    @Query("""
            SELECT l FROM AdmissionReservationStatusLog l
            WHERE l.reservation.id = :reservationId AND l.deleted = false
            ORDER BY l.createdAt DESC, l.id DESC
            """)
    List<AdmissionReservationStatusLog> findByReservationId(Long reservationId);
}
