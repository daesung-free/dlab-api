package com.dlab.domain.qna.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 대면 상담 예약.
 *
 * <p><b>취소를 물리 삭제하지 않는다</b> — "몇 번 예약했다 취소했나"가 운영 판단 근거가 된다.
 */
@Getter
@Entity
@Table(name = "qna_offline_reservation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QnaOfflineReservation extends BaseEntity {

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
    private QnaOfflineSlot slot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /**
     * 무엇을 물어볼지. ▷[0803] 회신의 "대면 신청 시 질문 입력"이다.
     *
     * <p>⚠️ <b>시트에는 없다</b>(회신서에만 있음). 없으면 담당이 준비를 못 하고 컬럼 하나라
     * 확정 후에도 손댈 일이 적어 미리 뒀다.
     */
    @Column(length = 500)
    private String question;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    public QnaOfflineReservation(QnaOfflineSlot slot, StudentEnrollment enrollment,
                                 String question, Instant reservedAt) {
        this.academy = slot.getAcademy();
        this.year = slot.getYear();
        this.slot = slot;
        this.enrollment = enrollment;
        this.question = question;
        this.reservedAt = reservedAt;
    }

    public void cancel(Instant at) {
        this.canceledAt = at;
    }

    public boolean isActive() {
        return canceledAt == null && !isDeleted();
    }
}
