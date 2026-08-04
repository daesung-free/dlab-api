package com.dlab.domain.master.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 커리큘럼 마스터 (F-4.10-1 기초 관리의 {@code curriculums}).
 *
 * <p>전년도 복사 의존순서에서 {@code class_group}(반) 다음이다 — <b>반을 참조</b>하므로
 * 반이 먼저 만들어져야 하고, 복사 시 참조를 새 연도 반으로 갈아끼워야 한다.
 *
 * <p><b>⚠️ 필드는 최소 구성이다.</b> 시트에 항목 수준 정의가 없어 이름·순서·반 참조만 뒀다.
 * 화면 요구사항이 구체화되면 컬럼을 더한다 — 억측으로 미리 넓히지 않는다.
 */
@Getter
@Entity
@Table(name = "curriculum")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Curriculum extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(nullable = false, length = 100)
    private String name;

    /** 소속 반. {@code null}이면 지점 공통이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id")
    private ClassMaster classMaster;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "copied_from_id")
    private Long copiedFromId;

    public Curriculum(Academy academy, short year, String name, ClassMaster classMaster,
                      short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.classMaster = classMaster;
        this.sortOrder = sortOrder;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void assignClass(ClassMaster classMaster) {
        this.classMaster = classMaster;
    }

    /** 전년도 복사가 호출한다. 원본 없이 만든 행은 계속 NULL이어야 한다. */
    public void markCopiedFrom(Long sourceId) {
        this.copiedFromId = sourceId;
    }
}
