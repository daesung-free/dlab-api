package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.EnrollmentStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentStatusHistoryRepository
        extends JpaRepository<EnrollmentStatusHistory, Long> {

    /** 이력은 지우지 않으므로 {@code deleted} 필터를 두지 않는다. */
    List<EnrollmentStatusHistory> findByEnrollmentIdOrderByCreatedAtDesc(Long enrollmentId);
}
