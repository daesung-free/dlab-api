package com.dlab.domain.facility.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassMaster;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 구역. 키오스크가 좌석 상태를 조회할 때 구역 단위로 묶는다.
 *
 * <p>스키마는 V2(다른 담당자)에서 왔다. 여기서는 <b>배정(쓰기)</b>만 다루고,
 * 키오스크 조회는 그쪽이 담당한다.
 *
 * <h2>★ 독서실과 반 교실이 같은 테이블에 있다</h2>
 * {@link AreaType}으로 가른다. 좌석 생성·격자·배치도·사용중지가 똑같아서 테이블을
 * 나누면 같은 코드를 한 벌 더 짜게 된다.
 *
 * <p>⚠️ <b>키오스크에는 {@code STUDY}만 내린다.</b> 교실이 섞여 내려가면 단말 좌석
 * 화면에 반이 뜬다 — 조회 경로마다 종류를 걸고 있는지 확인할 것.
 */
@Getter
@Entity
@Table(name = "study_area")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudyArea extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /**
     * 어느 관인가.
     *
     * <p>별관(동탄2관)이 본관과 <b>구역명까지 같아서</b> 구역만으로는 구분되지 않는다.
     * 기존 구역은 마이그레이션이 전부 본관에 붙였다.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false)
    private Building building;

    @Column(name = "area_cd", nullable = false, length = 50)
    private String areaCd;

    /**
     * 키오스크에 내리는 구역코드.
     *
     * <p>본관은 {@link #areaCd}와 같고 별관은 관 코드가 앞에 붙는다. 지점 안에서 유일하다
     * (UNIQUE) — 키오스크가 이 값으로 좌석을 조회하기 때문에 겹치면 단말에서 두 구역이
     * 하나로 합쳐져 보인다.
     */
    @Column(name = "kiosk_area_cd", nullable = false, length = 50)
    private String kioskAreaCd;

    @Column(name = "area_nm", nullable = false, length = 100)
    private String areaNm;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    /** 기본값은 {@code STUDY}다 — 이 컬럼이 생기기 전의 구역은 전부 독서실이었다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "area_type", nullable = false, length = 20)
    private AreaType areaType = AreaType.STUDY;

    /**
     * 반 참조. {@code CLASSROOM}일 때만 채운다.
     *
     * <p>DB가 {@code ck_study_area_class_ref}로 "교실이면 반이 있고 독서실이면 없다"를
     * 강제한다 — 반 없는 교실 구역이 생기면 배치도가 어느 반 것인지 알 수 없다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_master_id")
    private ClassMaster classMaster;

    /**
     * 구역 생성.
     *
     * <p>{@code areaCd}는 <b>DSA 체계를 그대로 쓴다</b> — 키오스크가 이 코드로 좌석을
     * 조회하므로 우리 내부 id로 바꾸면 안 된다(docs/dsa-compat.md 3.7·3.8).
     */
    /** 독서실 구역. 반 교실은 {@link #StudyArea(Building, String, String, String, short, AreaType, ClassMaster)}. */
    public StudyArea(Building building, String areaCd, String kioskAreaCd, String areaNm,
                     short sortOrder) {
        this(building, areaCd, kioskAreaCd, areaNm, sortOrder, AreaType.STUDY, null);
    }

    /**
     * 구역 생성 — 관과 종류까지.
     *
     * @param building    어느 관인가. 별관은 본관과 구역명이 겹칠 수 있다
     * @param kioskAreaCd 단말에 내릴 코드. 본관은 {@code areaCd}와 같다
     * @param areaType    {@code CLASSROOM}이면 {@code classMaster}가 있어야 한다
     * @param classMaster 반. {@code STUDY}면 {@code null}
     */
    public StudyArea(Building building, String areaCd, String kioskAreaCd, String areaNm,
                     short sortOrder, AreaType areaType, ClassMaster classMaster) {
        this.building = building;
        this.academy = building.getAcademy();
        this.areaCd = areaCd;
        this.kioskAreaCd = kioskAreaCd;
        this.areaNm = areaNm;
        this.sortOrder = sortOrder;
        this.active = true;
        this.areaType = areaType == null ? AreaType.STUDY : areaType;
        this.classMaster = classMaster;
    }

    public boolean isClassroom() {
        return areaType == AreaType.CLASSROOM;
    }

    /**
     * 구역 정보 수정.
     *
     * <p><b>{@code areaCd}는 바꾸지 않는다.</b> 키오스크가 이 코드로 좌석을 조회하므로
     * (3.7·3.8) 바꾸면 단말이 그 구역을 못 찾는다. 이름·정렬만 고친다.
     */
    public void update(String areaNm, Short sortOrder) {
        if (areaNm != null) {
            this.areaNm = areaNm;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
    }

    /**
     * 노출 여부.
     *
     * <p>비활성이면 관리자 화면 구역 목록에서 빠진다 — 삭제가 아니라 "쓰지 않는 구역"이다.
     */
    public void changeActive(boolean active) {
        this.active = active;
    }

    /**
     * 되살릴 때 코드가 아니라 이름·정렬만 새 값으로 덮는다.
     *
     * <p>관도 바꾸지 않는다 — 같은 코드라도 <b>다른 관이면 다른 구역</b>이라 되살리기의
     * 대상이 아니다(조회 자체가 관 안에서 이뤄진다).
     */
    public void reviveAs(String areaNm, short sortOrder) {
        reviveAs(areaNm, sortOrder, AreaType.STUDY, null);
    }

    /**
     * 되살리기 — 종류까지 덮는다.
     *
     * <p><b>종류도 새 값으로 간다.</b> 지운 코드를 재사용하면서 용도를 바꾸는 경우가 있는데
     * (반을 없애고 그 자리에 독서실 구역), 옛 종류가 남으면 키오스크 노출 여부가 뒤집힌다.
     */
    public void reviveAs(String areaNm, short sortOrder, AreaType areaType,
                         ClassMaster classMaster) {
        restore();
        this.areaNm = areaNm;
        this.sortOrder = sortOrder;
        this.active = true;
        this.areaType = areaType == null ? AreaType.STUDY : areaType;
        this.classMaster = classMaster;
    }
}
