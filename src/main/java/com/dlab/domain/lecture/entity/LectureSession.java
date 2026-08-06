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
