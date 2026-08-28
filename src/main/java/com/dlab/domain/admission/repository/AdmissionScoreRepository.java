package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdmissionScoreRepository extends JpaRepository<AdmissionScore, Long> {

    Optional<AdmissionScore> findByReservationIdAndScoreTypeAndSubjectAndDeletedFalse(
            Long reservationId, String scoreType, int subject);

    List<AdmissionScore> findByReservationIdAndDeletedFalse(Long reservationId);
}
