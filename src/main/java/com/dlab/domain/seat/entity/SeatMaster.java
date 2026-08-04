package com.dlab.domain.seat.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석. DSA {@code seat_cd} 체계.
 *
 * <p><b>좌표({@code x_pos}/{@code y_pos})는 키오스크가 좌석배치도를 그리는 데 쓴다</b> —
 * 없으면 화면이 비므로 반드시 내려줘야 한다.
 *
 * <p>{@link #usable}은 DSA {@code seat_gn}이다 — <b>좌석 자체의 사용 가능 여부</b>이고
 * 지금 누가 앉아 있는지(실시간 {@code state})와 다른 축이다. 섞지 말 것.
 */
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

    /** DSA {@code seat_gn}. 응답 시 {@code Y}/{@code N}으로 변환한다. */
    @Column(nullable = false)
    private boolean usable = true;

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

    /** DSA 응답용. 값이 없으면 클라이언트가 {@code Y}로 간주하지만 명시해서 내린다. */
    public String seatGn() {
        return usable ? "Y" : "N";
    }
}
