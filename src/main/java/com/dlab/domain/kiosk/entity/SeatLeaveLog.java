package com.dlab.domain.kiosk.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 좌석 이탈·복귀 로그 (F-4.3-2).
 *
 * <p>키오스크가 이탈/복귀 시점에 우리를 호출해 넣는다. <b>우리가 키오스크 DB를 조회하지
 * 않는 이유</b>는 그러면 우리 서버가 키오스크 스키마에 결합되어 그쪽 컬럼이 바뀔 때
 * 조용히 깨지기 때문이다 — 계약에 붙는 편이 낫다.
 *
 * <p><b>학생을 못 찾아도 행은 남긴다.</b> 거절하면 키오스크가 그 행을 영원히 재전송하며
 * 큐가 안 빠지고, 기록 자체도 사라져 나중에 원인을 찾을 수 없다.
 *
 * <p>⚠️ <b>이 로그를 읽는 쪽은 아직 없다.</b> 장시간 미복귀 감지는 임계값(I-16)과
 * 벌점 트리거 1차/2차 여부(I-5)가 확정돼야 만들 수 있다 — 임계값을 모르는 채로 스케줄러를
 * 짜면 기본값이 그대로 운영에 굳는다. 순공시간에는 <b>쓰이지 않는다</b>(1차 산식에서
 * 좌석이탈은 빼지 않기로 확정됐다).
 */
@Getter
@Entity
@Table(name = "seat_leave_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatLeaveLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    /** 키오스크 쪽 행 ID. {@code (academy, sourceRowId)}가 멱등키다. */
    @Column(name = "source_row_id", nullable = false)
    private Long sourceRowId;

    /** {@code null}이면 학생을 못 찾은 행이다. 원본 식별자로 나중에 이어 붙일 수 있다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private StudentEnrollment enrollment;

    @Column(name = "rfid_no", length = 50)
    private String rfidNo;

    @Column(name = "std_no", length = 20)
    private String studentNo;

    /** 키오스크가 주는 코드 그대로. 우리 좌석 마스터와 조인하지 않는다. */
    @Column(name = "area_cd", length = 20)
    private String areaCd;

    @Column(name = "seat_cd", length = 20)
    private String seatCd;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 10)
    private SeatLeaveEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public SeatLeaveLog(Academy academy, short year, Long sourceRowId,
                        StudentEnrollment enrollment, String rfidNo, String studentNo,
                        String areaCd, String seatCd,
                        SeatLeaveEventType eventType, Instant occurredAt) {
        this.academy = academy;
        this.year = year;
        this.sourceRowId = sourceRowId;
        this.enrollment = enrollment;
        this.rfidNo = rfidNo;
        this.studentNo = studentNo;
        this.areaCd = areaCd;
        this.seatCd = seatCd;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    /** 나중에 학생을 찾아 이어 붙일 때. */
    public void resolveStudent(StudentEnrollment enrollment) {
        this.enrollment = enrollment;
    }
}
