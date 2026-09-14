package com.dlab.domain.user.entity;

import com.dlab.domain.audit.AuditEntityListener;
import com.dlab.domain.audit.Audited;
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
@Audited("학생 등록")
@Entity
@Table(name = "student_enrollment")
@EntityListeners(AuditEntityListener.class)
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

    /**
     * N수 차수 — 1=재수, 2=삼수, 3=사수.
     *
     * <p><b>{@link GradeType}에 넣지 않았다.</b> 그쪽은 시험 양식의 축이라 값을 더하면
     * 양식이 학년 수만큼 곱해져 늘고 연도마다 다시 넣어야 하는데, <b>재수든 삼수든 치는
     * 시험은 같다</b> — 축이 다른 값이다. 사수·오수로 계속 늘어나는 것도 enum 에 맞지 않는다.
     *
     * <p><b>{@code null}은 "해당 없음이거나 아직 안 받은 것"</b>이다. 0으로 채우면
     * 현역과 구분되지 않는다.
     */
    @Column(name = "retake_count")
    private Short retakeCount;

    public void changeRetakeCount(Short retakeCount) {
        this.retakeCount = retakeCount;
    }

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

    public void changeTrack(TrackType track) {
        this.track = track;
    }

    /** 키오스크 카드 발급·재발급. */
    public void assignCard(String rfidNo) {
        this.rfidNo = rfidNo;
    }

    /**
     * 퇴원·제적 시 그날 날짜를 남긴다. 상태만으로는 <b>언제</b> 나갔는지 알 수 없어
     * 재원 기간 산정(환불 일할계산 등)이 불가능하다.
     */
    public void markWithdrawn(java.time.LocalDate date) {
        this.withdrawalDate = date;
        this.current = false;
    }

    /**
     * 입학(등원 시작)일. 등록 건이 만들어질 때 남긴다.
     *
     * <p><b>{@code created_at}으로 대신할 수 없다.</b> 소급 등록이 실제로 있고
     * (데스크가 며칠 뒤에 입력한다), 교습비 일할계산·재원기간 산정이 이 값을 본다.
     */
    public void recordAdmission(java.time.LocalDate date) {
        this.admissionDate = date;
    }

    /** 이 기수가 끝나 다음 기수로 넘어갈 때 이전 등록 건을 내린다. */
    public void expire() {
        this.current = false;
    }

    /**
     * 현재 등록으로 되돌린다.
     *
     * <p><b>잘못 만든 재등록을 지웠을 때 쓴다.</b> 재등록은 직전 건을 내리는데, 새로 만든
     * 것을 다시 지우면 <b>아무 등록도 current 가 아닌 상태</b>가 남는다 — 앱은 통째로
     * {@code ENROLLMENT_NOT_FOUND} 를 받고 관리자 화면에도 학생이 안 보인다. 되돌릴 방법이
     * 화면에 없어서 DB 를 직접 고쳐야 했다.
     *
     * <p>⚠️ <b>종료 상태는 되살리지 않는다.</b> 퇴원한 등록 건이 current 로 돌아오면
     * 퇴원생 카드로 키오스크 태깅이 통과한다(§3).
     */
    public void makeCurrent() {
        if (enrollmentStatus.requiresCleanup()) {
            return;
        }
        this.current = true;
    }


    /**
     * 등록 건 수정. {@code null}은 "변경하지 않음"이다({@link Student#updateProfile} 참고).
     *
     * <p>학번은 여기서 못 바꾼다 — 서버가 채번하고 {@code UNIQUE(academy_id, year, student_no)}로
     * 지키는 값이라, 임의 수정을 열면 중복·건너뜀이 생긴다.
     */
    public void updateEnrollment(GradeType grade, TrackType track, EnrollmentStatus status) {
        if (grade != null) {
            this.grade = grade;
        }
        if (track != null) {
            this.track = track;
        }
        if (status != null) {
            this.enrollmentStatus = status;
        }
    }
}
