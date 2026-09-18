package com.dlab.domain.admission.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수시/정시 지원대학과 그 결과 = 실적 (F-4.10-6).
 *
 * <h2>★ 지원과 실적을 두 테이블로 나누지 않는다</h2>
 * 지원한 대학 목록에 합불이 붙으면 그게 곧 실적이다. 따로 두면 <b>같은 대학을 두 번
 * 입력</b>하게 되고, 둘이 어긋났을 때 어느 쪽이 맞는지 알 수 없다.
 *
 * <h2>사람이 아니라 등록 건에 붙는다</h2>
 * 그 해 입시 결과다. 재수로 다시 등록하면 <b>전년도 지원 이력이 올해 실적에 섞이면 안
 * 된다</b>(`docs/entity-design.md` O).
 */
@Getter
@Entity
@Table(name = "admission_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdmissionResult extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "admission_type", nullable = false, length = 10)
    private AdmissionType admissionType;

    @Column(name = "university_name", nullable = false, length = 100)
    private String universityName;

    @Column(name = "department_name", nullable = false, length = 100)
    private String departmentName;

    /** 전형명. <b>매년 바뀌어 마스터를 두지 않는다</b> — 자동완성은 쌓인 값에서 만든다 */
    @Column(name = "track_name", length = 100)
    private String trackName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AdmissionResultStatus result = AdmissionResultStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AdmissionSource source = AdmissionSource.STAFF;

    @Column(length = 500)
    private String memo;

    public AdmissionResult(StudentEnrollment enrollment, AdmissionType admissionType,
                           String universityName, String departmentName, String trackName,
                           AdmissionResultStatus result, AdmissionSource source, String memo) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.admissionType = admissionType;
        this.universityName = universityName;
        this.departmentName = departmentName;
        this.trackName = trackName;
        this.result = result == null ? AdmissionResultStatus.PENDING : result;
        this.source = source == null ? AdmissionSource.STAFF : source;
        this.memo = memo;
    }

    /**
     * 수정. 비워 보낸 항목은 바꾸지 않는다.
     *
     * <p>★ <b>수정하면 입력 주체가 직원이 된다.</b> 0826 규칙이 *"처음 입력시 학생, 이후
     * 수정시에는 직원을 통해서"* 라, 고친 뒤에도 학생 입력으로 남아 있으면 그 값이
     * 확인을 거쳤는지 알 수 없다.
     */
    public void update(AdmissionType admissionType, String universityName,
                       String departmentName, String trackName,
                       AdmissionResultStatus result, String memo) {
        if (admissionType != null) {
            this.admissionType = admissionType;
        }
        if (universityName != null && !universityName.isBlank()) {
            this.universityName = universityName;
        }
        if (departmentName != null && !departmentName.isBlank()) {
            this.departmentName = departmentName;
        }
        if (trackName != null) {
            this.trackName = trackName;
        }
        if (result != null) {
            this.result = result;
        }
        this.memo = memo;
        this.source = AdmissionSource.STAFF;
    }
}
