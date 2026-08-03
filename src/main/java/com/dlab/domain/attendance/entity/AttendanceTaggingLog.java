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
 * 태깅 원장 — 실제로 발생한 이벤트만 쌓는다.
 *
 * <p>결석은 여기 들어오지 않는다. "안 찍은 것"이라 로그에 남을 수 없고,
 * {@link AttendanceDailyStatus}가 배치로 확정한다.
 */
@Getter
@Entity
@Table(name = "attendance_tagging_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceTaggingLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Convert(converter = AttendanceEventTypeConverter.class)
    @Column(name = "event_type", nullable = false, length = 1)
    private AttendanceEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceSource source;

    /** 엔티티로 매핑하지 않는다 — 아직 키오스크 기기 관리 기능이 없다. */
    @Column(name = "kiosk_device_id")
    private Long kioskDeviceId;

    @Column(name = "period_id")
    private Long periodId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /**
     * recordedAt에서 뽑은 파생 컬럼.
     * 미등원 배치·일별 집계가 전부 날짜 기준이라 timestamptz를 매번 캐스팅하면 인덱스를
     * 못 타고, 야간 자습 때문에 자정 경계 처리도 애매해진다.
     */
    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    public AttendanceTaggingLog(Academy academy, short year, StudentEnrollment enrollment,
                                AttendanceEventType eventType, AttendanceSource source,
                                Instant recordedAt, LocalDate attendanceDate) {
        this.academy = academy;
        this.year = year;
        this.enrollment = enrollment;
        this.eventType = eventType;
        this.source = source;
        this.recordedAt = recordedAt;
        this.attendanceDate = attendanceDate;
    }
}
