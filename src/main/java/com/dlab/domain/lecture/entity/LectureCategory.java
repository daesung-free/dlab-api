package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 특강 유형 세분류 — 단과 · 실전 · 해설.
 *
 * <p><b>{@link LectureType}과 다른 축이다.</b> 그쪽은 특강 / 설명회로 <b>화면의 탭</b>이고,
 * 여기는 그 특강이 어떤 종류인가다. 합치면 탭이 5개가 되고 "설명회 중 해설"처럼
 * 없는 조합이 생긴다. 설명회에는 세분류가 붙지 않는다.
 *
 * <p><b>값을 코드에 박지 않는다.</b> 발주 회신이 <i>"추가할 수 있게"</i>였다 —
 * enum이면 하나 늘 때마다 마이그레이션을 쓰고 배포해야 한다.
 * {@code AbsenceReasonCategory}·{@code penalty_item}과 같은 방식이다.
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

    /** 중지하면 새로 고를 수 없고, 이미 그 유형인 특강은 그대로 남는다. */
    @Column(nullable = false)
    private boolean active = true;

    public LectureCategory(Academy academy, short year, String name, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    /** {@code null}은 "안 바꿈"이다. */
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

    public boolean isCommon() {
        return academy == null;
    }
}
