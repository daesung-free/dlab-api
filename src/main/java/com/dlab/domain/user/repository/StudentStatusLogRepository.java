package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.StudentStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudentStatusLogRepository extends JpaRepository<StudentStatusLog, Long> {

    List<StudentStatusLog> findByEnrollmentIdAndDeletedFalseOrderByChangedAtDesc(Long enrollmentId);
}
