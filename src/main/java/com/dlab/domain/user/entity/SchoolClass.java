package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 반. 담임(담당선생님·사감)을 여기서 지정하고, 학생을 반에 배정하면
 * 방화벽 에스컬레이션 승인자가 자동으로 정해진다(CLAUDE.md §3).
 * 그래서 "학생별 승인자 사전지정 UI"는 만들지 않는다.
 */
@Getter
@Entity
@Table(name = "school_class")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SchoolClass extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "school_year", nullable = false)
    private int schoolYear;

    /** 담임 = 담당선생님(사감). 미지정일 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "homeroom_staff_id")
    private Staff homeroomStaff;

    public SchoolClass(Branch branch, String name, int schoolYear, Staff homeroomStaff) {
        this.branch = branch;
        this.name = name;
        this.schoolYear = schoolYear;
        this.homeroomStaff = homeroomStaff;
    }

    public void assignHomeroomStaff(Staff staff) {
        this.homeroomStaff = staff;
    }
}
