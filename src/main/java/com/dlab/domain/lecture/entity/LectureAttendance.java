package com.dlab.domain.lecture.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 특강 회차별 출석.
 *
 * <p>클라이언트가 DSA 기능확인 회신에서 <b>"특강출석부"를 사용 항목으로 직접 추가</b>했다.
 */
@Getter
@Entity
@Table(name = "lecture_attendance")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureAttendance extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private LectureSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private LectureApplication application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LectureAttendanceStatus status = LectureAttendanceStatus.PRESENT;

    @Column(length = 200)
    private String memo;

    public LectureAttendance(LectureSession session, LectureApplication application,
                             LectureAttendanceStatus status, String memo) {
        this.academy = session.getAcademy();
        this.year = session.getYear();
        this.session = session;
        this.application = application;
        this.status = status;
        this.memo = memo;
    }

    public void change(LectureAttendanceStatus status, String memo) {
        this.status = status;
        this.memo = memo;
    }
}
