package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학생 메모 (2026-10-13 디랩 피드백 5-2 「비고/메모」).
 *
 * <h2>★★ 칸 하나로 두지 않는다</h2>
 * 단일 문자열이면 <b>서로 덮어쓰고 누가 언제 썼는지가 사라진다.</b> 디랩은 「비고」라고만
 * 적었지만 운영에서는 <b>상담 메모처럼</b> 쓰이고 담당자가 여럿이다.
 * 입학예약 메모({@code AdmissionReservationMemo})와 같은 모양으로 쌓는다.
 *
 * <h2>★ 사람이 아니라 등록 건에 붙인다</h2>
 * 학생이 다음 기수에 재등록하면 <b>그해 메모가 새 기수에 섞이면 안 된다</b> —
 * 학번·카드가 등록 건에 붙는 것과 같은 판단이다(CLAUDE.md §3).
 */
@Getter
@Entity
@Table(name = "student_memo")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentMemo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(nullable = false, length = 2000)
    private String content;

    public StudentMemo(StudentEnrollment enrollment, String content) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.content = content;
    }
}
