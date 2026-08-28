package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 홈페이지 입학예약 드롭다운 코드 (규격서 3.5·3.6).
 *
 * <p>⚠️ <b>실제 값 목록을 아직 못 받았다.</b> DSA에 있던 데이터다. 비어 있으면 홈페이지
 * 드롭다운이 빈 채로 뜬다 — 받는 대로 행만 넣으면 되고 코드 변경은 필요 없다.
 */
@Getter
@Entity
@Table(name = "common_code")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommonCode extends BaseEntity {

    /** 출신학원 · 지원기준 · 전형 · 알게된경로. */
    public static final String GRP_ACAD = "ACAD";
    public static final String GRP_ADMI = "ADMI";
    public static final String GRP_EXAM = "EXAM";
    public static final String GRP_FIND = "FIND";
    /** 과목(3.6). 다른 넷과 응답 형태가 달라 그룹만 같은 표에 둔다. */
    public static final String GRP_SUBJECT = "SUBJECT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String grp;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** 과목만 쓴다. 국어 1 · 수학 2 · 영어 3 · 탐구1 4 · 탐구2 5. */
    @Column(name = "idx")
    private Short idx;

    /** {@code null}이면 전 지점 공통. 지점마다 다른 항목이 있으면 값이 붙는다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private Academy academy;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    public CommonCode(String grp, String code, String name, Short idx,
                      Academy academy, short sortOrder) {
        this.grp = grp;
        this.code = code;
        this.name = name;
        this.idx = idx;
        this.academy = academy;
        this.sortOrder = sortOrder;
    }
}
