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

    /**
     * 누가 바꿨나 — <b>{@code created_by} 로는 구분되지 않는다.</b>
     *
     * <p>공개 링크는 인증 주체가 없어 시스템 계정({@code 0})으로 기록되고, 배치·DSA 호환
     * 구획도 같은 값이다. 전화 받아 적는 일을 없애려고 <b>학생 응답이 상태를 바로 바꾸되</b>,
     * 직원이 덮은 변경과 구분돼 남아야 한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 10)
    private StatusActorType actorType = StatusActorType.STAFF;

    /**
     * 학생 응답이 들어온 IP. 직원 변경이면 {@code null}.
     *
     * <p>토큰이 새면 남이 누를 수 있고, 1회 고정이라 피해가 1건으로 끝나지만
     * <b>그 1건이 어디서·언제 들어왔는지는 보여야 한다</b>(시각은 {@code created_at}).
     */
    @Column(name = "actor_ip", length = 45)
    private String actorIp;

    /** 직원 변경. */
    public AdmissionReservationStatusLog(AdmissionReservation reservation,
                                         ConsultStatus fromStatus, ConsultStatus toStatus,
                                         String reason) {
        this(reservation, fromStatus, toStatus, reason, StatusActorType.STAFF, null);
    }

    public AdmissionReservationStatusLog(AdmissionReservation reservation,
                                         ConsultStatus fromStatus, ConsultStatus toStatus,
                                         String reason, StatusActorType actorType,
                                         String actorIp) {
        this.reservation = reservation;
        this.academy = reservation.getAcademy();
        this.year = reservation.getYear();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.actorType = actorType;
        this.actorIp = actorIp;
    }
}
