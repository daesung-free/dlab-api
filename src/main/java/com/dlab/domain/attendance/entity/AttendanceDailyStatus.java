package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일자 집계 — 배치가 태깅 원장을 보고 확정하는 파생 상태.
 * ABSENT는 오직 여기에만 존재한다.
 */
@Getter
@Entity
@Table(name = "attendance_daily_status")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class
AttendanceDailyStatus extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_status", nullable = false, length = 20)
    private DailyStatus finalStatus;

    /** 순공시간(분). 하원(T) 태깅이 있어야 계산된다. */
    @Column(name = "study_minutes")
    private Integer studyMinutes;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt = Instant.now();

    /**
     * 사유 승인 여부. {@link #finalStatus}와 <b>직교하는 축</b>이다 —
     * 결석뿐 아니라 지각·조퇴에도 붙는다.
     *
     * <p>이걸로 <b>무단과 사유 승인분을 가른다</b>:
     * <ul>
     *   <li>벌점 자동부여 — 무단만 대상</li>
     *   <li>미등원 알림 — 무단만 대상</li>
     *   <li><b>키오스크 {@code absence_cnt} — 둘 다 합산</b>(규격서·키오스크 모두 "결석 횟수"다)</li>
     * </ul>
     */
    @Column(name = "is_excused", nullable = false)
    private boolean excused;

    /**
     * 관리자가 손댄 행인가.
     *
     * <p><b>확정 배치가 이 행을 건너뛴다.</b> 배치는 매일 새벽 원장을 다시 훑어
     * {@link #reconfirm}으로 덮어쓰는데, 그러면 관리자가 낮에 고쳐놓은 값이
     * <b>다음 날 새벽에 조용히 원래대로 돌아간다.</b> 화면에는 정정된 값이 보이다가
     * 하루 뒤 뒤집히므로 아무도 원인을 못 찾는다.
     */
    @Column(name = "manually_modified", nullable = false)
    private boolean manuallyModified;

    public AttendanceDailyStatus(Academy academy, StudentEnrollment enrollment,
                                 LocalDate attendanceDate, DailyStatus finalStatus) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.attendanceDate = attendanceDate;
        this.finalStatus = finalStatus;
    }

    public AttendanceDailyStatus(Academy academy, StudentEnrollment enrollment,
                                 LocalDate attendanceDate, DailyStatus finalStatus,
                                 boolean excused) {
        this(academy, enrollment, attendanceDate, finalStatus);
        this.excused = excused;
    }

    /**
     * 재확정. <b>배치를 다시 돌려도 행이 늘지 않게</b> 기존 행을 덮어쓴다 —
     * 사유가 뒤늦게 승인되면 무단결석이 사유결석으로 바뀌어야 한다.
     */
    /**
     * 순공시간 저장.
     *
     * <p><b>계산해서 저장한다 — 조회 때마다 다시 계산하지 않는다.</b> 이유 둘:
     * <ul>
     *   <li>키오스크 순위·평균이 지점 전체 × 5일을 훑는다. 매번 계산하면 비용이 크다</li>
     *   <li><b>교시를 수정하면 과거 순공시간이 소급해서 바뀐다.</b> 급식·쉬는시간을
     *       교시 마스터에서 빼기 때문이다 — 저장해두면 그 시점 기준으로 고정된다</li>
     * </ul>
     */
    public void recordStudyMinutes(int minutes, Instant at) {
        this.studyMinutes = minutes;
        this.calculatedAt = at;
    }

    public void reconfirm(DailyStatus finalStatus, boolean excused, Instant at) {
        this.finalStatus = finalStatus;
        this.excused = excused;
        this.calculatedAt = at;
    }

    /**
     * 관리자 정정.
     *
     * <p>{@link #reconfirm}과 값을 바꾸는 건 같지만 <b>이후 배치가 덮지 못하게</b>
     * 표시한다는 점이 다르다. 정정 이력은 {@code attendance_modification}에 따로 쌓인다 —
     * 이 행에는 마지막 값만 남아 "무엇이 무엇으로 바뀌었나"를 답할 수 없다.
     */
    public void correct(DailyStatus finalStatus, boolean excused, Instant at) {
        this.finalStatus = finalStatus;
        this.excused = excused;
        this.calculatedAt = at;
        this.manuallyModified = true;
    }
}
