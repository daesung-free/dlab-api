package com.dlab.domain.report.repository;

import com.dlab.domain.report.entity.DailyReportFeedback;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyReportFeedbackRepository extends JpaRepository<DailyReportFeedback, Long> {

    Optional<DailyReportFeedback> findByEnrollmentIdAndReportDateAndDeletedFalse(
            Long enrollmentId, LocalDate reportDate);

    /** 달력 뷰 — 한 달치를 한 번에. 날짜별로 다시 부르면 30번 왕복한다. */
    List<DailyReportFeedback> findByEnrollmentIdAndReportDateBetweenAndDeletedFalse(
            Long enrollmentId, LocalDate from, LocalDate to);
}
