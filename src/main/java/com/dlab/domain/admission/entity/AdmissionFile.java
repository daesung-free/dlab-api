package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입학예약 성적표 파일 (규격서 3.8).
 *
 * <p><b>파일 본문은 여기 없다.</b> Base64 성적표가 수 MB라 행이 부풀고 백업·복제 비용이
 * 그대로 늘어난다. 저장 위치만 남기고 바이트는 저장소 구현체가 갖는다.
 */
@Getter
@Entity
@Table(name = "admission_file")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionFile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private AdmissionReservation reservation;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "byte_size")
    private Long byteSize;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    public AdmissionFile(AdmissionReservation reservation, String contentType,
                         long byteSize, String storageKey) {
        this.reservation = reservation;
        this.contentType = contentType;
        this.byteSize = byteSize;
        this.storageKey = storageKey;
    }
}
