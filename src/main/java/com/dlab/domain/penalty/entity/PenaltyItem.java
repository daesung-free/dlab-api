package com.dlab.domain.penalty.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 상벌점 항목 마스터. 전년도 복사 대상이다. */
@Getter
@Entity
@Table(name = "penalty_item")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PenaltyItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    @Column(name = "point_value", nullable = false)
    private int pointValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PenaltyCategory category;

    public PenaltyItem(Academy academy, short year, String itemName, int pointValue, PenaltyCategory category) {
        this.academy = academy;
        this.year = year;
        this.itemName = itemName;
        this.category = category;
        this.pointValue = signed(pointValue, category);
    }

    /**
     * 항목 수정.
     *
     * <p><b>이미 부여된 상벌점에는 소급되지 않는다</b> — 부여 시점에 점수를 복사해
     * {@code penalty_point.points}에 남기기 때문이다. 항목 점수를 3점에서 5점으로
     * 바꿔도 지난달에 받은 학생은 3점 그대로다.
     */
    public void change(String itemName, int pointValue, PenaltyCategory category) {
        this.itemName = itemName;
        this.category = category;
        this.pointValue = signed(pointValue, category);
    }

    /**
     * ★ <b>부호를 구분에 맞춰 저장한다.</b>
     *
     * <p>화면이 벌점을 양수(5점)로 보내든 음수(-5점)로 보내든 저장은 음수로 통일한다.
     * 통계·합계가 <b>부호로 상점과 벌점을 가르기</b> 때문에, 벌점이 양수로 들어가면
     * 그 학생의 벌점이 상점으로 집계된다 — 화면에는 "벌점 5점"으로 정상으로 보인다.
     */
    private static int signed(int pointValue, PenaltyCategory category) {
        int magnitude = Math.abs(pointValue);
        return category == PenaltyCategory.DEMERIT ? -magnitude : magnitude;
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

}
