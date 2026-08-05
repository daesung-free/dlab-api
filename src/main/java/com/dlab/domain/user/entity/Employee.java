package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 직원 — 행정 조직. <b>행정선생님이 여기 들어온다</b>(담당선생님은 {@link Teacher}).
 *
 * <p>공지 작성 권한이 두 테이블에 걸친다 — 전체공지는 행정선생님, 반공지는 담당선생님이다.
 *
 * <p>겸직 케이스가 확인되지 않아 단일 지점을 유지한다. 겸직이 필요해지면
 * {@code Teacher}와 동일 패턴(배정 테이블)으로 전환할 것.
 */
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

    public void updateProfile(String deptName, String positionName, String phone, String email) {
        this.deptName = deptName;
        this.positionName = positionName;
        this.phone = phone;
        this.email = email;
    }

    public Employee(Academy academy, String name) {
        this.academy = academy;
        this.name = name;
    }
}
