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

    /**
     * 승인 주체 여부 — <b>학생당 최대 1명</b>(I-12 0803 "학부모 최대 1인").
     *
     * <p><b>연결 자체를 1건으로 묶지 않는 이유</b>: 연락처는 부·모를 따로 보관해야 한다.
     * DSA {@code getParentHpList}가 관계 코드와 함께 목록으로 내리고 키오스크의
     * "부모에게 전화 걸기" 화면이 그걸 쓴다 — 1건으로 묶으면 번호가 하나만 뜬다.
     *
     * <p>"최대 1인"이 실제로 막아야 하는 것은 <b>승인 요청을 누구에게 보낼지가 갈리는 것</b>이다.
     */
    @Column(name = "is_approver", nullable = false)
    private boolean approver = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public StudentGuardianLink(Student student, ParentGuardian guardian, short relationOrder) {
        this(student, guardian, relationOrder, false);
    }

    public StudentGuardianLink(Student student, ParentGuardian guardian, short relationOrder,
                               boolean approver) {
        this.student = student;
        this.guardian = guardian;
        this.relationOrder = relationOrder;
        this.approver = approver;
    }

    @NoArgsConstructor
    public static class Key implements Serializable {
        private Long student;
        private Long guardian;
    }
}
