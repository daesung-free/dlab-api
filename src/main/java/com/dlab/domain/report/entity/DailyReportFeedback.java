package com.dlab.domain.report.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학생 셀프 피드백 (F-4.11-6, 앱 A-3).
 *
 * <p><b>하루 1행이다.</b> 여러 번 쓰면 마지막 것만 남는다 — "그날의 회고"라 이력을
 * 쌓을 대상이 아니고, 쌓으면 달력이 어느 것을 보여줄지 정해야 한다.
 *
 * <p><b>관리자가 고치지 않는다.</b> 담임이 손대면 회고가 아니라 제출물이 되어
 * 아무도 솔직하게 안 쓴다.
 */
@Getter
@Entity
@Table(name = "daily_report_feedback")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReportFeedback extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(nullable = false, length = 500)
    private String content;

    public DailyReportFeedback(StudentEnrollment enrollment, LocalDate reportDate,
                               String content) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.reportDate = reportDate;
        this.content = content;
    }

    public void rewrite(String content) {
        this.content = content;
    }
}
