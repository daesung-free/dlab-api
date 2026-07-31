package com.dlab.domain.attendance.entity;

import com.dlab.common.entity.BaseTimeEntity;
import com.dlab.domain.user.entity.Branch;
import com.dlab.domain.user.entity.Student;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 하루 1건의 등원 기록. 재태깅은 새로 쌓지 않는다
 * (미등원 판정 기준이 흔들리면 안 되므로 (student, date) 유니크).
 */
@Getter
@Entity
@Table(name = "attendance_record")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttendanceRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceStatus status;

    public AttendanceRecord(Branch branch, Student student, LocalDate attendanceDate,
                            Instant checkedInAt, AttendanceSource source, AttendanceStatus status) {
        this.branch = branch;
        this.student = student;
        this.attendanceDate = attendanceDate;
        this.checkedInAt = checkedInAt;
        this.source = source;
        this.status = status;
    }
}
