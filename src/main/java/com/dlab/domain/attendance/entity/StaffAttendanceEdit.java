package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 직원 출근 기록 수정 이력 (긴급출입 2-4).
 *
 * <p>요구사항이 「수정 이력을 남깁니다」라고 하는데 <b>수정 기능 자체가 없었다.</b>
 *
 * <h2>★ 왜 남기는가</h2>
 * 「이 사람이 진짜 그 시간에 왔는가」에 답하기 위해서다. <b>고친 값은 원본보다 신뢰도가
 * 낮은데, 이력이 없으면 그 사실 자체가 사라진다</b> — 누가 봐도 태깅된 값처럼 보인다.
 *
 * <p>전후값을 <b>둘 다</b> 남긴다. 후값만 남기면 원본을 알 길이 없고, 전값만 남기면
 * 지금 값이 맞는지 모른다.
 */
@Getter
@Entity
@Table(name = "staff_attendance_edit")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffAttendanceEdit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id", nullable = false)
    private StaffAttendance attendance;

    @Enumerated(EnumType.STRING)
    @Column(name = "before_event_type", nullable = false, length = 10)
    private StaffAttendanceType beforeEventType;

    @Column(name = "before_recorded_at", nullable = false)
    private Instant beforeRecordedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "after_event_type", nullable = false, length = 10)
    private StaffAttendanceType afterEventType;

    @Column(name = "after_recorded_at", nullable = false)
    private Instant afterRecordedAt;

    /**
     * 고친 이유 — <b>필수</b>.
     *
     * <p>요구사항의 「누가·언제·무엇을·왜」 중 <b>「왜」가 없으면 나머지 셋이 쓸모없어진다.</b>
     */
    @Column(nullable = false, length = 500)
    private String reason;

    public StaffAttendanceEdit(StaffAttendance attendance,
                               StaffAttendanceType beforeEventType, Instant beforeRecordedAt,
                               StaffAttendanceType afterEventType, Instant afterRecordedAt,
                               String reason) {
        this.attendance = attendance;
        this.academy = attendance.getAcademy();
        this.year = attendance.getYear();
        this.beforeEventType = beforeEventType;
        this.beforeRecordedAt = beforeRecordedAt;
        this.afterEventType = afterEventType;
        this.afterRecordedAt = afterRecordedAt;
        this.reason = reason;
    }
}
