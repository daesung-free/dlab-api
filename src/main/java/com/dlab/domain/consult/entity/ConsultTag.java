package com.dlab.domain.consult.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상담 항목 태그 (마스터).
 *
 * <p><b>자유 서술로 두면 집계가 안 된다.</b> "성적 하락 걱정" / "성적이 떨어져 고민" /
 * "성적저하"가 전부 다른 문자열이 된다. 관리자가 항목을 만들고 담임은 고르기만 한다.
 *
 * <p>목록 자체는 아직 못 받았다 — 상위 유형 5종만 확정이다. <b>데이터로 뒀으므로
 * 확정되면 행만 넣으면 된다</b>({@code penalty_rule}과 같은 방식).
 */
@Getter
@Entity
@Table(name = "consult_tag")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultTag extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    /** 어느 유형에 붙는 태그인지. {@code null}이면 모든 유형에서 쓴다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "consult_type", length = 20)
    private ConsultType consultType;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    /** 화면 노출 상한. 태그가 쌓이면 담임이 못 찾는다. */
    @Column(name = "max_display", nullable = false)
    private short maxDisplay = 10;

    @Column(nullable = false)
    private boolean active = true;

    public ConsultTag(Academy academy, short year, ConsultType consultType,
                      String name, short sortOrder) {
        this.academy = academy;
        this.year = year;
        this.consultType = consultType;
        this.name = name;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    public void update(String name, ConsultType consultType, short sortOrder,
                       short maxDisplay, boolean active) {
        this.name = name;
        this.consultType = consultType;
        this.sortOrder = sortOrder;
        this.maxDisplay = maxDisplay;
        this.active = active;
    }
}
