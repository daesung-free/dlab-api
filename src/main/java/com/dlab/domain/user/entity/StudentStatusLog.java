package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 재적 상태 전이 이력 (F-4.1-8).
 *
 * <p>현재 상태만 보면 <b>"퇴원 처리가 실수였는지 정당했는지"</b>에 답할 수 없다.
 * 누가 바꿨는지는 {@code created_by}가 자동으로 채운다(직접 넣지 말 것).
 */
@Getter
@Entity
@Table(name = "student_status_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentStatusLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private EnrollmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private EnrollmentStatus toStatus;

    @Column(length = 200)
    private String reason;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public StudentStatusLog(StudentEnrollment enrollment, EnrollmentStatus fromStatus,
                            EnrollmentStatus toStatus, String reason, Instant changedAt) {
        this.academy = enrollment.getAcademy();
        this.enrollment = enrollment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedAt = changedAt;
    }
}
