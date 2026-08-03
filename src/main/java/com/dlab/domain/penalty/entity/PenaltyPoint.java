package com.dlab.domain.penalty.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 실제 부여된 상벌점.
 *
 * <p>자동 부여는 반드시 멱등해야 한다. 출결·루틴 이벤트가 중복 트리거되면 점수가 두 번
 * 부여되는데(키오스크 재태깅·배치 재실행·다중 인스턴스), 애플리케이션 레벨 체크로는
 * 동시 실행을 막을 수 없다. {@code idempotencyKey}의 DB 유니크 제약이 최종 방어선이다.
 */
@Getter
@Entity
@Table(name = "penalty_point")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PenaltyPoint extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "penalty_item_id", nullable = false)
    private PenaltyItem penaltyItem;

    @Column(nullable = false)
    private int points;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PenaltySource source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    /** 자동 부여 중복 방지 키. 수기 부여는 null. */
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    public PenaltyPoint(Academy academy, StudentEnrollment enrollment,
                        PenaltyItem penaltyItem, int points, String reason,
                        PenaltySource source, String idempotencyKey) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.penaltyItem = penaltyItem;
        this.points = points;
        this.reason = reason;
        this.source = source;
        this.idempotencyKey = idempotencyKey;
    }

    /** 출결 트리거 자동 부여용 멱등키. */
    public static String attendanceKey(Long enrollmentId, LocalDate date, Long ruleId) {
        return "ATTENDANCE:%d:%s:%d".formatted(enrollmentId, date, ruleId);
    }
}
