package com.dlab.domain.seat.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석 배정 <b>이력</b>. 현재 배정은 {@code releasedAt IS NULL}인 행이다.
 *
 * <p><b>덮어쓰지 않는다.</b> 좌석 이동({@code setSeatChgProc})이 수시로 일어나는데,
 * 나중에 "그 시각에 누가 어느 좌석이었나"를 출결·자리이탈과 대조해야 한다.
 *
 * <p>배정 대상은 사람이 아니라 <b>등록 건</b>이다 — 1년 코호트라 좌석도 매년 새로 배정되고,
 * 사람에 붙이면 작년 배정이 남는다.
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

    @Column(name = "released_at")
    private Instant releasedAt;

    public SeatAssignment(Academy academy, SeatMaster seat, StudentEnrollment enrollment) {
        this.academy = academy;
        this.seat = seat;
        this.enrollment = enrollment;
        this.assignedAt = Instant.now();
    }

    public void release(Instant at) {
        this.releasedAt = at;
    }

    public boolean isActive() {
        return releasedAt == null;
    }
}
