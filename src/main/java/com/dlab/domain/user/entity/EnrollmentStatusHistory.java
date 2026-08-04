package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재원 상태 변경 이력.
 *
 * <p>{@code student_enrollment}은 현재 상태만 들고 있어 <b>"누가·언제·왜 퇴원 처리했나"</b>에
 * 답할 수 없다. {@code created_by}는 등록 시점 작성자라 이후 변경자를 남기지 못한다.
 * 상태 변경은 환불·재등록 분쟁으로 직결돼 근거가 남아야 한다.
 *
 * <p><b>이력행은 지우지 않는다.</b> 지우면 이력을 남긴 의미가 없다.
 */
@Getter
@Entity
@Table(name = "enrollment_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EnrollmentStatusHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 지점별 집계가 잦아 비정규화해 둔다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    private EnrollmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private EnrollmentStatus toStatus;

    /** 효력 발생일. 처리일({@code createdAt})과 다를 수 있다 — 소급 처리가 실제로 있다. */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(length = 500)
    private String reason;

    public EnrollmentStatusHistory(StudentEnrollment enrollment, EnrollmentStatus fromStatus,
                                   EnrollmentStatus toStatus, LocalDate effectiveDate, String reason) {
        this.academy = enrollment.getAcademy();
        this.enrollment = enrollment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.effectiveDate = effectiveDate;
        this.reason = reason;
    }
}
