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

    /**
     * 키오스크에 내리는 좌석번호.
     *
     * <p>본관은 {@link #seatCd}와 같고 <b>별관은 관 offset 이 더해진 값</b>이다(1번 →
     * 1001번 — DSA 가 쓰던 방식 그대로라 기존 운영과 값이 같다).
     *
     * <p><b>계산해서 내리지 않고 저장하는 이유</b>: 본관에 이미 1001번이 있으면 별관 1번의
     * 변환 결과와 겹치는데, 즉석 계산이면 그 충돌이 <b>단말에서만</b> 드러난다. 컬럼으로
     * 두면 {@code UNIQUE (academy_id, kiosk_seat_cd)}가 등록 시점에 막는다.
     */
    @Column(name = "kiosk_seat_cd", nullable = false, length = 50)
    private String kioskSeatCd;

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
    public SeatMaster(StudyArea studyArea, String seatCd, String kioskSeatCd, String seatNm,
                      int xPos, int yPos) {
        this.academy = studyArea.getAcademy();
        this.studyArea = studyArea;
        this.seatCd = seatCd;
        this.kioskSeatCd = kioskSeatCd;
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
     * <p>유니크가 부분 인덱스가 된 지금은 새 행을 넣어도 제약에 걸리지 않는다. 그래도
     * 되살리는 이유는 <b>{@code seat_assignment}가 이 행을 참조</b>하기 때문이다 — 새 행을
     * 만들면 같은 자리인데 과거 배정이 다른 좌석에 붙어 이력이 갈린다.
     *
     * <p>{@code seatCd}·{@code kioskSeatCd}는 덮지 않는다. 같은 코드를 찾아 온 것이라
     * 이미 같은 값이고, 관이 다르면 애초에 이 좌석을 찾지 않는다.
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
