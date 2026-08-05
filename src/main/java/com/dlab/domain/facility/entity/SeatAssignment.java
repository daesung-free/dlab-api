package com.dlab.domain.facility.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 좌석 배정.
 *
 * <p>현재 배정 여부를 <b>{@code releasedAt IS NULL}</b>로 표현한다(V2 스키마 규약).
 * 좌석당·학생당 현재 배정이 하나뿐이라는 부분 유니크 인덱스가 걸려 있다.
 */
@Getter
@Entity
@Table(name = "seat_assignment")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatAssignment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private SeatMaster seat;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt = Instant.now();

    /** null이면 현재 배정 중. */
    @Column(name = "released_at")
    private Instant releasedAt;

    public SeatAssignment(Academy academy, SeatMaster seat, StudentEnrollment enrollment) {
        this.academy = academy;
        this.seat = seat;
        this.enrollment = enrollment;
    }

    public void release(Instant at) {
        this.releasedAt = at;
    }

    public boolean isActive() {
        return releasedAt == null;
    }
}
