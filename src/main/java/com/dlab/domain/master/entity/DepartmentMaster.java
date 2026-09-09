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

    /**
     * 전년도 복사 원본. NULL이면 그 해에 새로 만든 것이다.
     * 복사본과 신규 생성분을 구분할 유일한 근거라 복사 시 반드시 채운다.
     */
    @Column(name = "copied_from_id")
    private Long copiedFromId;

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }


    /** 코드 · 비고 · 사용여부. 네 마스터가 같은 세 칸을 공유한다. */
    @Embedded
    private MasterAttributes attributes = MasterAttributes.empty();

    public String getCode() {
        return attributes.getCode();
    }

    public String getMemo() {
        return attributes.getMemo();
    }

    public boolean isActive() {
        return attributes.isActive();
    }

    /** {@code null}은 "안 바꿈"이 아니라 "지움"이다 — 화면은 항상 현재 값을 실어 보낸다. */
    public void updateAttributes(String code, String memo) {
        this.attributes.update(code, memo);
    }

    public void changeActive(boolean active) {
        this.attributes.changeActive(active);
    }
}
