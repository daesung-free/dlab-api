package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 전형 마스터.
 *
 * <p>학생이 어떤 전형으로 들어왔는지를 {@code student_enrollment}가 참조한다 —
 * 사람이 아니라 <b>등록 건</b>에 붙는다. 그 해 입학 방식이라 삼수생의 1년차와 2년차가 다를 수 있다.
 */
@Getter
@Entity
@Table(name = "admission_type")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionType extends BaseEntity {

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

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "copied_from_id")
    private Long copiedFromId;

    public AdmissionType(Academy academy, short year, String name, short sortOrder) {
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
}
