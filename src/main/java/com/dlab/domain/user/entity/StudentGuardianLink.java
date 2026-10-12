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
     * 승인 주체 여부 — 학생당 최대 1명(I-12 0803 "학부모 최대 1인").
     *
     * <h2>⚠️⚠️ 승인 판정에 쓰지 말 것 (2026-10-12)</h2>
     * <b>0803 「최대 1인」이 뒤집혔다.</b> 디랩 회신은 <b>부·모 양쪽에 알림이 가고 먼저
     * 승인한 쪽으로 확정</b>된다. 그래서 {@code ApprovalService.resolveApproverType} 은
     * <b>그 학생에 연결돼 있으면 통과</b>시키고 이 컬럼을 보지 않는다.
     *
     * <p><b>*"컬럼이 있는데 왜 안 쓰지"* 라며 조건을 넣으면 선착순 승인이 깨진다</b> —
     * 지정되지 않은 쪽(대개 아버지)이 승인하면 {@code NOT_AN_APPROVER} 가 난다.
     * {@code GuardianRaceApprovalTest} 가 그걸 막는다.
     *
     * <h2>그래도 컬럼을 남기는 이유</h2>
     * 연락처는 부·모를 따로 보관해야 한다. DSA {@code getParentHpList}가 관계 코드와 함께
     * 목록으로 내리고 키오스크의 "부모에게 전화 걸기" 화면이 그걸 쓴다 — 1건으로 묶으면
     * 번호가 하나만 뜬다. 부분 유니크 제약도 그래서 그대로 둔다.
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
