package com.dlab.domain.audit;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 감사 로그 한 줄 (F-C-1 금일 수정 이력).
 *
 * <p><b>{@code created_by}로는 답이 안 된다.</b> 그건 {@code updatable = false}라 최초
 * 작성자만 남는다 — "누가 이 학생 벌점을 지웠나"에 답하려면 변경 시점마다 행이 생겨야 한다.
 */
@Getter
@Entity
@Table(name = "audit_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 60)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AuditAction action;

    /** 지점 필터용. 엔티티가 지점을 모르면 비어 있다(마스터·전 지점 공통). */
    @Column(name = "academy_id")
    private Long academyId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** 계정이 지워져도 "누가"가 남아야 한다. */
    @Column(name = "actor_name", length = 50)
    private String actorName;

    @Column(name = "actor_ip", length = 45)
    private String actorIp;

    /** 바뀐 필드만. 민감 필드는 값 대신 {@code ***}다. */
    @Column(columnDefinition = "text")
    private String changes;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /**
     * 대상 학생(등록 건). 학생에 붙은 기록(벌점·사유신청·청구·성적)만 채워진다.
     * 이름·학번은 저장하지 않고 조회할 때 붙인다 — 이력으로 개인정보가 복제되지 않게.
     */
    @Column(name = "target_enrollment_id")
    private Long targetEnrollmentId;

    /** 대상 학생을 붙인다. 생성자 인자를 늘리지 않으려고 따로 둔다. */
    public AuditLog withTarget(Long enrollmentId) {
        this.targetEnrollmentId = enrollmentId;
        return this;
    }

    public AuditLog(String entityType, Long entityId, AuditAction action, Long academyId,
                    Long actorId, String actorName, String actorIp,
                    String changes, Instant occurredAt) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.academyId = academyId;
        this.actorId = actorId;
        this.actorName = actorName;
        this.actorIp = actorIp;
        this.changes = changes;
        this.occurredAt = occurredAt;
    }
}
