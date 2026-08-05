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
}
