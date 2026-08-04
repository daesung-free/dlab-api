package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 기숙사 배정 이력.
 *
 * <p><b>퇴실해도 행을 지우지 않는다</b> — 언제 누가 어느 방을 썼는지가 남아야 한다.
 * 현재 사용 중인 건은 {@code releasedAt == null}이고,
 * 부분 유니크 인덱스({@code uq_dorm_assignment_active})가 한 학생의 동시 배정을 막는다.
 */
@Getter
@Entity
@Table(name = "dorm_assignment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DormAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private DormRoom room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "released_at")
    private Instant releasedAt;

    public DormAssignment(Academy academy, DormRoom room, StudentEnrollment enrollment) {
        this.academy = academy;
        this.room = room;
        this.enrollment = enrollment;
    }

    public void release(Instant at) {
        this.releasedAt = at;
    }

    public boolean isActive() {
        return releasedAt == null;
    }
}
