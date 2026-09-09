package com.dlab.domain.admission.repository;

import com.dlab.domain.admission.entity.AdmissionFile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdmissionFileRepository extends JpaRepository<AdmissionFile, Long> {

    List<AdmissionFile> findByReservationIdAndDeletedFalse(Long reservationId);
}
