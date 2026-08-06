package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 출결 정정 이력.
 *
 * <p><b>원장은 고치지 않으므로 여기가 유일한 추적 경로다.</b> 출결은 벌점·미등원 알림·
 * 출결률의 근거라 "누가 언제 무엇을 무엇으로 바꿨나"에 답하지 못하면 안 된다.
 *
 * <p>정정은 두 종류이고 한 행에 섞이지 않는다:
 * <ul>
 *   <li><b>상태 정정</b> — {@code beforeStatus}/{@code afterStatus}가 찬다</li>
 *   <li><b>태깅 보정</b> — {@code addedEvent}/{@code addedAt}이 찬다</li>
 * </ul>
 */
@Getter
@Entity
@Table(name = "attendance_modification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceModification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private Short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_status", length = 20)
    private DailyStatus beforeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_status", length = 20)
    private DailyStatus afterStatus;

    @Column(name = "before_excused")
    private Boolean beforeExcused;

    @Column(name = "after_excused")
    private Boolean afterExcused;

    @Enumerated(EnumType.STRING)
    @Column(name = "added_event", length = 20)
    private AttendanceEventType addedEvent;

    @Column(name = "added_at")
    private Instant addedAt;

    /** 정정 사유. 필수다 — 없으면 이력이 감사 자료가 되지 못한다. */
    @Column(nullable = false, length = 200)
    private String reason;

    private AttendanceModification(StudentEnrollment enrollment, LocalDate attendanceDate,
                                   String reason) {
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.enrollment = enrollment;
        this.attendanceDate = attendanceDate;
        this.reason = reason;
    }

    /** 상태 정정 이력. */
    public static AttendanceModification ofStatusChange(
            StudentEnrollment enrollment, LocalDate attendanceDate,
            DailyStatus beforeStatus, Boolean beforeExcused,
            DailyStatus afterStatus, boolean afterExcused, String reason) {

        AttendanceModification modification =
                new AttendanceModification(enrollment, attendanceDate, reason);
        modification.beforeStatus = beforeStatus;
        modification.beforeExcused = beforeExcused;
        modification.afterStatus = afterStatus;
        modification.afterExcused = afterExcused;
        return modification;
    }

    /** 태깅 보정 이력. */
    public static AttendanceModification ofTaggingAdded(
            StudentEnrollment enrollment, LocalDate attendanceDate,
            AttendanceEventType event, Instant at, String reason) {

        AttendanceModification modification =
                new AttendanceModification(enrollment, attendanceDate, reason);
        modification.addedEvent = event;
        modification.addedAt = at;
        return modification;
    }
}
