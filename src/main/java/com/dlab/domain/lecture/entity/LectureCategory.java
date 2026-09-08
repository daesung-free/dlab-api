package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 특강 유형 — 단과 · 실전 · 해설 …
 *
 * <p><b>{@link LectureType}과 다른 축이다.</b> 그쪽은 "특강이냐 설명회냐"로 흐름이 같아
 * 한 테이블에 두고 구분만 하는 값이라 늘어날 일이 없다. 여기는 <b>특강 안에서의 성격</b>이고
 * 학원이 늘린다. 합치면 "설명회 · 단과 · 실전"이 한 목록에 섞여 화면이 무엇을 고르는
 * 자리인지 흐려진다.
 *
 * <p><b>enum 에 박지 않는다.</b> 세 가지 고정이 아니라 관리자가 추가하는 값이라,
 * enum 이면 유형을 하나 늘릴 때마다 마이그레이션을 새로 쓰고 배포해야 한다.
 */
@Getter
@Entity
@Table(name = "lecture_category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureCategory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code null}이면 전 지점 공통. 지점마다 다르게 쓰면 그 지점만 채운다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder = 0;

    /** 끄면 새 특강에서 안 보인다. 지우지 않는 이유는 과거 특강이 이 행을 참조해서다. */
    @Column(nullable = false)
    private boolean active = true;

    public LectureCategory(Academy academy, short year, String name, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public void update(String name, Short sortOrder, Boolean active) {
        if (name != null) {
            this.name = name;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
        if (active != null) {
            this.active = active;
        }
    }
}
