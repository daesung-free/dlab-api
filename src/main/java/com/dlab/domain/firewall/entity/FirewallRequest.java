package com.dlab.domain.firewall.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 와이파이 방화벽 해제 신청.
 *
 * <p><b>승인 로직을 갖지 않는다</b> — {@link ApprovalRequest}에 위임하고 여기는 해제 자체의
 * 고유 정보(요청 시간·해제 구간·Zyxel 대상)만 담는다. 사유신청·정기일정도 같은 승인 엔진을
 * 타므로, 승인 로직을 여기 두면 세 번 짜게 된다.
 */
@Getter
@Entity
@Table(name = "firewall_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FirewallRequest extends BaseEntity {

    /** 최대 해제 시간(분) = 5시간. */
    public static final int MAX_REQUESTED_MINUTES = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /** 승인 상태·케이스는 전부 여기서 읽는다. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "approval_request_id", nullable = false, unique = true)
    private ApprovalRequest approvalRequest;

    @Column(name = "requested_minutes", nullable = false)
    private short requestedMinutes;

    @Column(length = 500)
    private String reason;

    @Column(name = "unlock_start_at")
    private Instant unlockStartAt;

    @Column(name = "unlock_end_at")
    private Instant unlockEndAt;

    /** Nebula API 호출 대상. 크레덴셜·제어 단위는 E-1 미해결. */
    @Column(name = "zyxel_site_id", length = 32)
    private String zyxelSiteId;

    /**
     * 해제 상태. <b>승인 상태와 별개다</b> — 승인됐어도 시간이 지나면 닫혀야 한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "unlock_status", nullable = false, length = 20)
    private UnlockStatus unlockStatus = UnlockStatus.WAITING;

    /**
     * 학생이 지정한 해제 구간.
     *
     * <p><b>{@code null}이면 승인 즉시 시작</b>이다(종전 방식). 값이 있으면 그 시각이 되어야
     * 열린다 — "3시에 쓸 건데 미리 신청"이 되어야 한다는 요청이다.
     *
     * <p>⚠️ <b>실제 해제는 아직 붙어 있지 않다.</b> 승인 확정 후 {@link #activate}를 부르는
     * 경로도, Nebula {@code unlock} 호출도 없다(E-1 자격증명 대기 — 지금은 {@code block}만
     * 목업으로 있다). 그래서 <b>이 값은 저장·표시까지만 유효하고</b>, 예약 시작 배치는
     * 해제 연동이 붙을 때 함께 만든다. 지금 배치만 먼저 만들면 부를 대상이 없다.
     */
    @Column(name = "requested_start_at")
    private Instant requestedStartAt;

    @Column(name = "requested_end_at")
    private Instant requestedEndAt;

    public FirewallRequest(Academy academy, StudentEnrollment enrollment,
                           ApprovalRequest approvalRequest, short requestedMinutes, String reason) {
        this.academy = academy;
        this.enrollment = enrollment;
        this.approvalRequest = approvalRequest;
        this.requestedMinutes = requestedMinutes;
        this.reason = reason;
    }

    /**
     * 구간 지정 신청.
     *
     * <p>{@code requestedMinutes}는 구간에서 계산해 함께 채운다 — 두 값이 어긋나면
     * 어느 쪽이 진실인지 판정할 수 없다.
     */
    public void requestWindow(Instant startAt, Instant endAt) {
        this.requestedStartAt = startAt;
        this.requestedEndAt = endAt;
        this.requestedMinutes = (short) java.time.Duration.between(startAt, endAt).toMinutes();
    }

    /** 지정 구간이 있는 신청인가. */
    public boolean hasRequestedWindow() {
        return requestedStartAt != null;
    }

    /**
     * 승인 확정 후 실제 해제 구간을 기록한다.
     *
     * <p><b>지정 구간이 있으면 그 구간을 쓴다.</b> 승인 시각부터 열면 학생이 적어낸
     * 시간대와 어긋나 "15시에 열어달라"가 13시에 열리는 일이 생긴다.
     */
    public void activate(Instant startAt) {
        if (hasRequestedWindow()) {
            this.unlockStartAt = requestedStartAt;
            this.unlockEndAt = requestedEndAt;
        } else {
            this.unlockStartAt = startAt;
            this.unlockEndAt = startAt.plusSeconds(requestedMinutes * 60L);
        }
        this.unlockStatus = UnlockStatus.ACTIVE;
    }

    /** 만료 차단 완료. 스케줄러가 Nebula 차단을 보낸 뒤 호출한다. */
    public void expire() {
        this.unlockStatus = UnlockStatus.EXPIRED;
    }

    public void cancel() {
        this.unlockStatus = UnlockStatus.CANCELED;
    }

    public boolean isActive() {
        return unlockStatus == UnlockStatus.ACTIVE;
    }
}
