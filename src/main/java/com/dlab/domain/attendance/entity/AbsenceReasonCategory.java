package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사유 카테고리 — 병결 · 가정사 · 학교행사 같은 "왜 그랬는가".
 *
 * <p><b>{@link AbsenceReasonType}과 다른 축이다.</b> 그쪽은 결석·지각·조퇴·외출로
 * "무엇을 했는가"다 — 같은 결석이라도 사유는 갈린다. 합치면 값이 4종 × 사유 수만큼
 * 늘고, 통계에서 "지각인데 병결"을 셀 수 없다.
 *
 * <p><b>값을 코드에 박지 않는다.</b> 어떤 카테고리를 쓸지 아직 안 정해졌고
 * (클라이언트가 "기본 카테고리가 있거나"라고만 했다) 지점·연도마다 다를 수 있다.
 * 상벌점 항목과 같은 방식으로 데이터에 둔다.
 */
@Getter
@Entity
@Table(name = "absence_reason_category")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AbsenceReasonCategory extends BaseEntity {

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

    /** 끄면 새 신청에서 안 보인다. 지우지 않는 이유는 과거 사유가 이 행을 참조해서다. */
    @Column(nullable = false)
    private boolean active = true;

    public AbsenceReasonCategory(Academy academy, short year, String name, short sortOrder) {
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
