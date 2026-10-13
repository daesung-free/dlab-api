package com.dlab.domain.profile.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 목표대학 한 줄 + 합격증 (F-4.11-9).
 *
 * <h2>★ 전형은 자유 문자열이다</h2>
 * 전국 대학 전형명은 <b>매년 바뀌어</b> 마스터로 두면 갱신 부담이 그대로 운영 부담이 된다 —
 * 0821 회신에 그렇게 제안해 뒀다. 직원이 정리한 명칭이 다음 학생부터 자동완성으로 쌓이는
 * 방향이고, 그 자동완성도 <b>이 칸에 쌓인 값</b>에서 나온다.
 *
 * <h2>★★ 합격증은 {@code file_attachment} id 만 참조한다</h2>
 * 새 저장소를 만들지 않는다. 만들면 용량·형식 검증과 EXIF 제거, 보관기간 정리를 이
 * 도메인에서 <b>다시 짜게 되고 한 곳만 빠뜨리면 그때부터 검증되지 않은 파일이 들어온다</b>.
 */
@Getter
@Entity
@Table(name = "student_profile_university")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TargetUniversity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private StudentProfile profile;

    /** 화면의 줄 순서(1지망·2지망). 지망 컬럼을 따로 두면 같은 뜻이 두 곳에 생긴다. */
    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "university_name", nullable = false, length = 100)
    private String universityName;

    @Column(name = "department_name", length = 100)
    private String departmentName;

    @Column(name = "screening_name", length = 100)
    private String screeningName;

    /** 합격증. 파일 본문은 {@code domain/file} 이 갖는다 — 여기는 참조만 한다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certificate_file_id")
    private FileAttachment certificateFile;

    /**
     * 직원의 합불 확인 (0821 — 학생 입력 → 직원이 합불 확인).
     *
     * <p>★ <b>{@code null} 이 「아직 확인 안 함」이다.</b> {@code false} 로 채우면
     * <b>불합격으로 읽혀 없는 사실이 생긴다</b>.
     */
    @Column
    private Boolean passed;

    public TargetUniversity(StudentProfile profile, short sortOrder, String universityName,
                            String departmentName, String screeningName,
                            FileAttachment certificateFile, Boolean passed) {
        this.profile = profile;
        this.academy = profile.getAcademy();
        this.year = profile.getYear();
        this.sortOrder = sortOrder;
        this.universityName = universityName;
        this.departmentName = departmentName;
        this.screeningName = screeningName;
        this.certificateFile = certificateFile;
        this.passed = passed;
    }
}
