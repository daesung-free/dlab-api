package com.dlab.domain.consult.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상담 예약.
 *
 * <p><b>취소를 물리 삭제하지 않는다</b> — "몇 번 잡았다 취소했나"가 운영 판단 근거가 된다.
 *
 * <p><b>상담 일지를 여기서 가리킨다.</b> {@code consult_log} 쪽에 예약 컬럼을 더하지 않은
 * 이유는, 일지가 예약 없이도 만들어지기 때문이다(전화 상담 등) — 그쪽에 두면 대부분 NULL이다.
 */
@Getter
@Entity
@Table(name = "consult_reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ConsultSlot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "consult_type", nullable = false, length = 20)
    private ConsultType consultType;

    /** 무엇을 상담하고 싶은지. 없으면 담임이 준비를 못 한다. */
    @Column(name = "request_note", length = 500)
    private String requestNote;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    /** 상담을 마치고 담임이 쓴 일지. {@code null}이면 아직 안 썼거나 노쇼다. */
    @Column(name = "consult_log_id")
    private Long consultLogId;

    public ConsultReservation(ConsultSlot slot, StudentEnrollment enrollment,
                              ConsultType consultType, String requestNote, Instant reservedAt) {
        this.academy = slot.getAcademy();
        this.year = slot.getYear();
        this.slot = slot;
        this.enrollment = enrollment;
        this.consultType = consultType;
        this.requestNote = requestNote;
        this.reservedAt = reservedAt;
    }

    public void cancel(Instant at) {
        this.canceledAt = at;
    }

    public void linkLog(Long consultLogId) {
        this.consultLogId = consultLogId;
    }

    public boolean isActive() {
        return canceledAt == null && !isDeleted();
    }
}
