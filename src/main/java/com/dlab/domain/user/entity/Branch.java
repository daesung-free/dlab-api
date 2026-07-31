package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Getter
@Entity
@Table(name = "branch")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Branch extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 등원 기준시각(지점 공통, 학생별 아님). 이 시각에 미등원 감지 배치가 돈다. */
    @Column(name = "attendance_deadline", nullable = false)
    private LocalTime attendanceDeadline;

    @Column(nullable = false)
    private boolean active;

    public Branch(String name, LocalTime attendanceDeadline) {
        this.name = name;
        this.attendanceDeadline = attendanceDeadline;
        this.active = true;
    }
}
