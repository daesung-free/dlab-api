package com.dlab.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 학부모-자녀 연결. 앱의 자녀전환 UI가 이 목록을 쓴다.
 *
 * <p>등록 건이 아니라 <b>사람</b>(student)에 연결한다 — 자녀가 재등록해도 연결이 유지돼야 하기 때문.
 */
@Getter
@Entity
@Table(name = "student_guardian_link")
@IdClass(StudentGuardianLink.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentGuardianLink {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guardian_id", nullable = false)
    private ParentGuardian guardian;

    @Column(name = "relation_order", nullable = false)
    private short relationOrder = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public StudentGuardianLink(Student student, ParentGuardian guardian, short relationOrder) {
        this.student = student;
        this.guardian = guardian;
        this.relationOrder = relationOrder;
    }

    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long student;
        private Long guardian;
    }
}
