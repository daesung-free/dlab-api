package com.dlab.domain.attendance.repository;

import com.dlab.domain.attendance.entity.AbsenceReason;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AbsenceReasonRepository extends JpaRepository<AbsenceReason, Long> {

    List<AbsenceReason> findByStudentIdAndTargetDate(Long studentId, LocalDate targetDate);

    List<AbsenceReason> findByBranchIdAndTargetDate(Long branchId, LocalDate targetDate);
}
