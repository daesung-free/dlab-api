package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입학예약 지원기준 성적 (규격서 3.7).
 *
 * <p><b>점수를 숫자로 받지 않는다.</b> 규격이 String이고 등급·표준점수·백분위가 같은 칸에
 * 들어온다. 숫자로 강제하면 형식이 하나만 달라도 저장이 막힌다.
 */
@Getter
@Entity
@Table(name = "admission_score")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionScore extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private AdmissionReservation reservation;

    /** B 백분위 · D 등급 · P 표준점수 · PB 표준점수+백분위 · W 원점수. */
    @Column(name = "score_type", nullable = false, length = 2)
    private String scoreType;

    @Column(nullable = false)
    private int subject;

    @Column(nullable = false, length = 20)
    private String score;

    public AdmissionScore(AdmissionReservation reservation, String scoreType,
                          int subject, String score) {
        this.reservation = reservation;
        this.scoreType = scoreType;
        this.subject = subject;
        this.score = score;
    }

    /** 홈페이지가 수정 후 다시 보내면 덮어쓴다. */
    public void changeScore(String score) {
        this.score = score;
    }
}
