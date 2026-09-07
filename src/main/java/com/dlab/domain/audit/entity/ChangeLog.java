package com.dlab.domain.audit.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 변경 이력 (F-4.10-8 · 금일 수정 이력).
 *
 * <h2>왜 권한 전용이 아니라 범용인가</h2>
 * 첫 사용처는 계정 권한 변경이지만, 요구사항정의서에 <b>전 화면 변경 이력 조회</b>가
 * 따로 있고 그 실현 방식(전용 화면이냐 공통 뷰어냐)이 미확정이다(오픈이슈 #50).
 * 권한 전용으로 만들면 공통으로 갈 때 표가 두 벌이 되고 이미 쌓인 이력을 옮겨야 한다.
 *
 * <h2>늦을수록 비싸다</h2>
 * 나중에 붙여도 <b>안 남긴 기간은 복구할 수 없다</b> — "누가 이 계정에 SUPER_ADMIN을
 * 줬나"에 영영 답할 수 없게 된다.
 *
 * <p>{@code createdBy}(행위자)와 {@code createdAt}(시각)은 {@link BaseEntity}가
 * 자동으로 채운다 — <b>직접 넣지 말 것</b>.
 */
@Getter
@Entity
@Table(name = "change_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChangeLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 지점·연도는 <b>없을 수 있다</b> — 계정처럼 지점이 없는 대상이 있고,
     * 없는 지점을 지어내면 지점 필터가 오히려 틀어진다.
     */
    @Column(name = "academy_id")
    private Long academyId;

    @Column(name = "year")
    private Short year;

    /** {@code "ACCOUNT_ROLE"} 같은 대상 종류. enum이면 대상이 늘 때마다 마이그레이션을 쓴다. */
    @Column(name = "target_type", nullable = false, length = 40)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 사람이 읽는 대상 이름. <b>대상이 지워져도 이력은 읽혀야 한다.</b> */
    @Column(name = "target_label", length = 100)
    private String targetLabel;

    @Column(nullable = false, length = 40)
    private String action;

    /**
     * 변경 전·후. <b>구조화된 diff가 아니라 사람이 읽는 요약</b>이다 —
     * 대상마다 형태가 다르면 공통 뷰어가 한 표로 못 그린다.
     */
    @Column(name = "before_value", columnDefinition = "TEXT")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "TEXT")
    private String afterValue;

    @Column(length = 200)
    private String memo;

    public ChangeLog(Long academyId, Short year, String targetType, Long targetId,
                     String targetLabel, String action, String beforeValue, String afterValue) {
        this.academyId = academyId;
        this.year = year;
        this.targetType = targetType;
        this.targetId = targetId;
        this.targetLabel = targetLabel;
        this.action = action;
        this.beforeValue = beforeValue;
        this.afterValue = afterValue;
    }
}
