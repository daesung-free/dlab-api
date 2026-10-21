package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 특강 회차.
 *
 * <p>출석부가 회차 단위라 필요하다. 하루짜리 특강이면 회차가 1개다.
 */
@Getter
@Entity
@Table(name = "lecture_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lecture_id", nullable = false)
    private Lecture lecture;

    @Column(name = "session_no", nullable = false)
    private short sessionNo;

    @Column(name = "session_date", nullable = false)
    private LocalDate sessionDate;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(length = 50)
    private String room;

    /**
     * 그 회차 수강료 (디랩 1021 답변 Q1 — 「회차당 단가」 확정).
     *
     * <p>★ <b>{@code null} 은 미설정이다.</b> {@link Lecture#getFee()} 를 n등분하지 말 것 —
     * 나누어떨어지지 않는 회차에서 <b>합계가 정가와 어긋나고</b>, 그 차이를 누가 부담하는지가
     * 코드에만 남는다.
     */
    @Column(name = "fee")
    private Integer fee;

    /**
     * 회차 수정 (디랩 1021 답변 Q1 의 예외 요청).
     *
     * <p>디랩 원문: <i>"주1회 수요일 수업이었는데 모의고사 또는 개인 사유로 <b>2회차 수업을
     * 목요일에 한다</b>던지 … <b>수동으로 설정</b>할 수 있을까요?"</i>
     *
     * <p>★ <b>출석 기록이 있어도 날짜를 바꾼다.</b> 보강이라 같은 수업이고, 출석을 지우면
     * 그 회차에 왔던 학생 기록이 사라진다. 비워 보낸 항목은 바꾸지 않는다.
     */
    public void update(LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
                       String room, Integer fee) {
        if (sessionDate != null) {
            this.sessionDate = sessionDate;
        }
        if (startTime != null) {
            this.startTime = startTime;
        }
        if (endTime != null) {
            this.endTime = endTime;
        }
        if (room != null) {
            this.room = room;
        }
        if (fee != null) {
            this.fee = fee;
        }
    }

    public LectureSession(Lecture lecture, short sessionNo, LocalDate sessionDate,
                          LocalTime startTime, LocalTime endTime, String room) {
        this.academy = lecture.getAcademy();
        this.year = lecture.getYear();
        this.lecture = lecture;
        this.sessionNo = sessionNo;
        this.sessionDate = sessionDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.room = room;
    }
}
