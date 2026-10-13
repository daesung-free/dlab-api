package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 「다른 사람입니다」 판단 기록.
 *
 * <h2>★★ 이 판단을 남기지 않으면 같은 조합이 볼 때마다 다시 뜬다</h2>
 * 중복 후보는 <b>이름이 같으면 계속 걸린다.</b> 한 번 「다른 사람」으로 정리한 쌍이
 * 다음에 또 올라오면 담당자가 <b>매번 같은 판단을 다시</b> 해야 한다 — 그러면
 * 탐지가 도움이 아니라 방해가 된다.
 *
 * <h2>★ 이름이 아니라 「예약 ↔ 등록 건」 쌍으로 남긴다</h2>
 * 이름·생년월일 같은 값으로 남기면 ① <b>개인정보가 세 번째 테이블에 또 쌓이고</b>
 * ② <b>연락처가 바뀌는 순간 기록이 안 맞는다.</b> id 쌍은 바뀌지 않는다.
 *
 * <h2>⚠️ 신규접수는 판단 시점에 등록 건이 없다</h2>
 * 저장 <b>직전</b>에 막는 구조라 그렇다. 그래서 등록이 끝난 뒤에 쌍을 적는다 —
 * 그 한 번이 지나면 이후 전환·목록에서 같은 쌍이 걸러진다.
 */
@Getter
@Entity
@Table(name = "admission_duplicate_decision")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionDuplicateDecision extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(name = "year", nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private AdmissionReservation reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /**
     * 어느 화면에서 내린 판단인지.
     *
     * <p>신규접수와 전환은 서로 다른 흐름이라, 나중에 <b>「어디서 잘못 넘어갔나」</b>를
     * 되짚으려면 구분이 필요하다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "decided_on", nullable = false, length = 10)
    private DecidedOn decidedOn;

    public AdmissionDuplicateDecision(Academy academy, short year,
                                      AdmissionReservation reservation,
                                      StudentEnrollment enrollment, DecidedOn decidedOn) {
        this.academy = academy;
        this.year = year;
        this.reservation = reservation;
        this.enrollment = enrollment;
        this.decidedOn = decidedOn;
    }

    /** 판단이 내려진 화면. */
    public enum DecidedOn {
        /** 데스크 신규접수 중 — 예약이 걸렸는데 다른 사람이라고 확인했다 */
        ADMIT,
        /** 예약 전환 중 — 이미 등록된 학생이 걸렸는데 다른 사람이라고 확인했다 */
        CONVERT
    }
}
