package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입학 상담 메모.
 *
 * <h2>열람 범위는 테이블이 아니라 조회가 정한다</h2>
 * 지점 메모는 그 지점만, 본사는 전체다. <b>별도 권한 컬럼을 두지 않는다</b> —
 * {@code academy_id} 와 {@code SearchScope} 로 충분하고, 권한을 두 군데서 관리하면
 * 한쪽만 바뀐다.
 */
@Getter
@Entity
@Table(name = "admission_reservation_memo")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionReservationMemo extends BaseEntity {

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

    @Column(nullable = false, length = 2000)
    private String content;

    public AdmissionReservationMemo(AdmissionReservation reservation, String content) {
        this.reservation = reservation;
        this.academy = reservation.getAcademy();
        this.year = reservation.getYear();
        this.content = content;
    }

    public void edit(String content) {
        this.content = content;
    }
}
