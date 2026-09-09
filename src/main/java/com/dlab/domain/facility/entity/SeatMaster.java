package com.dlab.domain.facility.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 좌석. 좌표는 좌석배치도 렌더링용이다. */
@Getter
@Entity
@Table(name = "seat_master")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatMaster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_area_id", nullable = false)
    private StudyArea studyArea;

    @Column(name = "seat_cd", nullable = false, length = 50)
    private String seatCd;

    @Column(name = "seat_nm", length = 50)
    private String seatNm;

    @Column(name = "x_pos", nullable = false)
    private int xPos;

    @Column(name = "y_pos", nullable = false)
    private int yPos;

    /** 고장·공사 등으로 쓸 수 없는 좌석. 배정 대상에서 제외한다. */
    @Column(nullable = false)
    private boolean usable = true;

    /**
     * 좌석 생성.
     *
     * <p>좌표는 <b>키오스크가 좌석배치도를 그리는 데 쓴다</b> — 없으면 화면이 빈다.
     * {@code usable}은 DSA {@code seat_gn}에 대응한다(사용 {@code Y} / 미사용 {@code N}).
     */
    public SeatMaster(Academy academy, StudyArea studyArea, String seatCd, String seatNm,
                      int xPos, int yPos) {
        this.academy = academy;
        this.studyArea = studyArea;
        this.seatCd = seatCd;
        this.seatNm = seatNm;
        this.xPos = xPos;
        this.yPos = yPos;
        this.usable = true;
    }

    /**
     * 사용중지 — 고장·공사 등.
     *
     * <p><b>좌석을 지우지 않는다.</b> 지우면 배치도에 구멍이 생겨 좌표가 어긋나 보이고,
     * 그 자리에 앉았던 과거 배정 이력도 끊긴다. 배정 대상에서만 빠진다.
     */
    public void disable() {
        this.usable = false;
    }

    public void enable() {
        this.usable = true;
    }

    /**
     * 좌석 정보 수정.
     *
     * <p><b>{@code seatCd}는 바꾸지 않는다.</b> 키오스크가 이 코드로 좌석을 찾고
     * ({@code setSeatChgProc}) 좌석 상태 응답을 코드로 머지하기 때문에, 바꾸면 그 자리가
     * 단말에서 사라진다. 이름·좌표만 고친다.
     */
    public void update(String seatNm, Integer xPos, Integer yPos) {
        if (seatNm != null) {
            this.seatNm = seatNm;
        }
        if (xPos != null) {
            this.xPos = xPos;
        }
        if (yPos != null) {
            this.yPos = yPos;
        }
    }

    /**
     * 지웠던 좌석을 같은 코드로 되살린다.
     *
     * <p>{@code UNIQUE (academy_id, seat_cd)}가 soft delete를 모르기 때문에, 지운 좌석의
     * 코드로 다시 등록하면 새 행을 넣을 수 없다. 구역·이름·좌표를 새 값으로 덮어 되살린다.
     */
    public void reviveAs(StudyArea studyArea, String seatNm, int xPos, int yPos) {
        restore();
        this.studyArea = studyArea;
        this.seatNm = seatNm;
        this.xPos = xPos;
        this.yPos = yPos;
        this.usable = true;
    }
}
