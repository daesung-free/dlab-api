package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 학생 — <b>등록 건</b> 쪽 (기수별, 1인 N행).
 *
 * <p>학번과 RFID 카드번호가 사람이 아니라 여기 붙기 때문에 학번 매년 초기화가 구조적으로
 * 보장되고, 카드가 다음 기수에 재사용돼도 과거 기수 출결이 섞이지 않는다.
 *
 * <p>연도에 종속되는 데이터(출결·상벌점·반배정·청구)는 전부 {@link Student}가 아니라
 * 이 엔티티를 참조해야 한다.
 */
@Getter
@Entity
@Table(name = "student_enrollment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudentEnrollment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    /** 기수(등록년도). */
    @Column(name = "year", nullable = false)
    private short year;

    /** 학번. 매년 초기화되므로 PK·외부연동 키로 쓰지 말 것. */
    @Column(name = "student_no", length = 20)
    private String studentNo;

    /**
     * 키오스크 카드 태깅 매칭 키.
     * <b>UNIQUE가 아니다</b> — 등록 건마다 쌓이는 이력이라서다.
     * 카드번호로 학생을 찾을 때는 반드시 {@code isCurrent = true}로 걸러야 한다.
     * 빠뜨리면 퇴원생 카드로 태깅이 통과한다.
     */
    @Column(name = "rfid_no", length = 10)
    private String rfidNo;

    /** 현재 유효한 등록 건 플래그 (레거시 STAT_GB='T' 대응). */
    @Column(name = "is_current", nullable = false)
    private boolean current = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private GradeType grade;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TrackType track;

    /** 재원 상태. 가입 승인 상태(account.status)와 별개다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "enrollment_status", nullable = false, length = 20)
    private EnrollmentStatus enrollmentStatus = EnrollmentStatus.ENROLLED;

    @Column(name = "admission_date")
    private LocalDate admissionDate;

    @Column(name = "withdrawal_date")
    private LocalDate withdrawalDate;

    public StudentEnrollment(Student student, Academy academy, short year,
                             String studentNo, String rfidNo, GradeType grade) {
        this.student = student;
        this.academy = academy;
        this.year = year;
        this.studentNo = studentNo;
        this.rfidNo = rfidNo;
        this.grade = grade;
        this.current = true;
        this.enrollmentStatus = EnrollmentStatus.ENROLLED;
    }

    public String getStudentName() {
        return student.getName();
    }

    /** 이 기수가 끝나 다음 기수로 넘어갈 때 이전 등록 건을 내린다. */
    public void expire() {
        this.current = false;
    }
}
