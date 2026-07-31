package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "student")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_account_id", nullable = false, unique = true)
    private UserAccount userAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    /** 미배정 상태가 있을 수 있다. 배정되면 담당선생님(담임)이 자동으로 따라온다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private SchoolClass schoolClass;

    /** 학부모 자녀연결용 고유ID. 마이페이지에 상시 노출된다. */
    @Column(name = "public_code", nullable = false, unique = true, length = 20)
    private String publicCode;

    /** 학번. 매년 초기화되므로 식별키로 쓰지 말 것(CLAUDE.md §3). */
    @Column(name = "student_no", length = 20)
    private String studentNo;

    @Column(name = "school_year", nullable = false)
    private int schoolYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade_type", nullable = false, length = 20)
    private GradeType gradeType;

    public Student(UserAccount userAccount, Branch branch, String publicCode,
                   int schoolYear, GradeType gradeType) {
        this.userAccount = userAccount;
        this.branch = branch;
        this.publicCode = publicCode;
        this.schoolYear = schoolYear;
        this.gradeType = gradeType;
    }

    public String getName() {
        return userAccount.getName();
    }

    /** 반 배정. 이 시점부터 방화벽 에스컬레이션 승인자가 결정된다. */
    public void assignClass(SchoolClass schoolClass) {
        this.schoolClass = schoolClass;
    }

    /**
     * 담당선생님(사감). 반 배정에서 자동으로 도출되며, 미배정이거나 담임 미지정이면 null이다.
     * 방화벽 신청 시 이 값을 스냅샷으로 신청건에 박는다.
     */
    public Staff getHomeroomStaff() {
        return schoolClass == null ? null : schoolClass.getHomeroomStaff();
    }
}
