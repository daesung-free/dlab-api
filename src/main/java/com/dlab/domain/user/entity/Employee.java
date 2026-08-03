package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** 직원(선생님·관리자). */
@Getter
@Entity
@Table(name = "employee")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "employee_type", nullable = false, length = 20)
    private EmployeeType employeeType;

    @Column(name = "dept_name", length = 64)
    private String deptName;

    @Column(name = "position_name", length = 64)
    private String positionName;

    @Column(length = 20)
    private String phone;

    @Column(length = 128)
    private String email;

    @Column(name = "hired_date")
    private LocalDate hiredDate;

    @Column(name = "resigned_date")
    private LocalDate resignedDate;

    public Employee(Academy academy, String name, EmployeeType employeeType) {
        this.academy = academy;
        this.name = name;
        this.employeeType = employeeType;
    }
}
