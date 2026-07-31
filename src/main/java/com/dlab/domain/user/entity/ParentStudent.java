package com.dlab.domain.user.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 학부모-자녀 연결. 앱의 자녀전환 UI가 이 목록을 사용한다. */
@Getter
@Entity
@Table(name = "parent_student")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ParentStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "parent_id", nullable = false)
    private Parent parent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ParentStudent(Parent parent, Student student, boolean primary) {
        this.parent = parent;
        this.student = student;
        this.primary = primary;
    }
}
