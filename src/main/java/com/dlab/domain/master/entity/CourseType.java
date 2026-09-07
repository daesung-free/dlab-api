package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 과정 마스터 (종합반·단과 등).
 *
 * <p>전년도 복사 의존순서에서 {@code department} 다음, {@code class_group}(반) 앞이다 —
 * 반이 과정을 참조하므로 과정이 먼저 만들어져야 한다.
 */
@Getter
@Entity
@Table(name = "course_type")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseType extends BaseEntity {

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

    /** 이름순으로 두면 운영 감각과 어긋나서 순서를 따로 받는다. */
    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "copied_from_id")
    private Long copiedFromId;

    public CourseType(Academy academy, short year, String name, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void changeSortOrder(short sortOrder) {
        this.sortOrder = sortOrder;
    }

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
