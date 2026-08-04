package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학과. 지점·연도 단위 마스터라 <b>전년도 복사 대상</b>이다.
 *
 * <p>복사 의존순서에서 가장 앞에 온다 — 반(class_master)이 학과를 참조하게 되면
 * 학과가 먼저 있어야 하기 때문이다.
 */
@Getter
@Entity
@Table(name = "department_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepartmentMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(nullable = false, length = 50)
    private String name;

    public DepartmentMaster(Academy academy, short year, String name) {
        this.academy = academy;
        this.year = year;
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }
}
