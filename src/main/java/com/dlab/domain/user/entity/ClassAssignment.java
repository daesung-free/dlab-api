package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 반 배정 이력. 배정이 바뀌면 이전 행을 비활성으로 내리고 새 행을 넣는다
 * — 매년 전체 재세팅되는 구조라 이력이 남아야 한다.
 */
@Getter
@Entity
@Table(name = "class_assignment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClassAssignment extends BaseEntity {

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
    @JoinColumn(name = "class_id", nullable = false)
    private ClassMaster classMaster;

    @Enumerated(EnumType.STRING)
    @Column(name = "class_type", nullable = false, length = 10)
    private ClassType classType = ClassType.FIXED;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public ClassAssignment(Academy academy, StudentEnrollment enrollment,
                           ClassMaster classMaster, ClassType classType) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.classMaster = classMaster;
        this.classType = classType;
    }

    public void deactivate() {
        this.active = false;
    }

    /** 담당선생님(사감). 반 담임에서 자동 도출되며 미지정이면 null이다. */
    public Teacher getHomeroomTeacher() {
        return classMaster.getHomeroomTeacher();
    }
}
