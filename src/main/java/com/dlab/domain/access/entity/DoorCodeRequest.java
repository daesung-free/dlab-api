package com.dlab.domain.access.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 긴급 출입 요청 1건 (긴급출입 1-6).
 *
 * <p><b>제공·거절을 모두 남긴다.</b> 거절 건을 빼면 <b>번호를 두드려 보는 시도가 기록에서
 * 사라진다</b> — 무인증 경로라 그 시도가 실제로 들어온다.
 */
@Getter
@Entity
@Table(name = "door_code_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DoorCodeRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DoorCodeRequestResult result;

    /** 찾았을 때만 채운다. 못 찾은 거절 건에는 없다 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private StudentEnrollment enrollment;

    /**
     * 입력한 번호 — <b>마스킹</b>.
     *
     * <p>평문으로 두면 요청 기록이 곧 연락처 목록이 되고, 아예 안 남기면 거절 건에서
     * 「누가 시도했나」를 전혀 알 수 없다.
     */
    @Column(name = "phone_masked", length = 20)
    private String phoneMasked;

    /**
     * 제공한 번호가 어느 이력이었나.
     *
     * <p>★ <b>번호 자체는 남기지 않는다</b> — 남기면 암호화한 의미가 사라진다.
     * 「그때 어떤 번호였나」는 이 참조로 추적한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "door_code_id")
    private BranchDoorCode doorCode;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    /** 공개 경로라 로그인 주체가 없다. 공격 추적에 필요해 남긴다 */
    @Column(name = "requester_ip", length = 45)
    private String requesterIp;

    public DoorCodeRequest(Academy academy, short year, DoorCodeRequestResult result,
                           StudentEnrollment enrollment, String phoneMasked,
                           BranchDoorCode doorCode, Instant requestedAt, String requesterIp) {
        this.academy = academy;
        this.year = year;
        this.result = result;
        this.enrollment = enrollment;
        this.phoneMasked = phoneMasked;
        this.doorCode = doorCode;
        this.requestedAt = requestedAt;
        this.requesterIp = requesterIp;
    }
}
