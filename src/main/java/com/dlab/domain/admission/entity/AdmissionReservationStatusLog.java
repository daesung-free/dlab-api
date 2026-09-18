package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상담 진행 상태 변경 이력.
 *
 * <p>★ 없으면 <b>"왜 미등록으로 바뀌었나"</b> 에 답할 수 없다. {@code created_by} 는 등록
 * 시점 작성자라 이후 변경자를 남기지 못한다 — 재적 상태 이력과 같은 이유다(§2).
 */
@Getter
@Entity
@Table(name = "admission_reservation_status_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionReservationStatusLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private AdmissionReservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private ConsultStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private ConsultStatus toStatus;

    @Column(length = 500)
    private String reason;

    public AdmissionReservationStatusLog(AdmissionReservation reservation,
                                         ConsultStatus fromStatus, ConsultStatus toStatus,
                                         String reason) {
        this.reservation = reservation;
        this.academy = reservation.getAcademy();
        this.year = reservation.getYear();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
    }
}
