package com.dlab.domain.approval.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.appconfig.entity.Terms;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 학생별 우선 승인자 선택 + 동의 (I-20, 0803 답변서).
 *
 * <h2>★ 이건 설정이 아니라 동의다</h2>
 * <b>"학부모가 10분 안에 응답하지 않으면 직원에게 승인권이 넘어간다"에 동의한 기록</b>이다.
 * 그래서 덮어쓰지 않고 <b>행을 쌓는다</b> — 나중에 바꿨다고 이전 동의를 지우면
 * 그 기간에 무엇에 동의했는지 답할 수 없다. 약관({@code term_agreement})과 같은 방식이다.
 *
 * <p><b>현재값은 가장 최근 행</b>이다.
 *
 * <p><b>{@link ApproverType#AUTO}는 고를 수 없다.</b> 사람이 선택하는 값이 아니라
 * 정책이 정하는 것이고, 학생이 "자동 승인"을 고르게 하면 승인 절차가 무의미해진다.
 */
@Getter
@Entity
@Table(name = "approver_preference")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApproverPreference extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @Column(nullable = false)
    private short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApproverType preferred;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    /** 동의한 안내 문구 버전. 문구가 미확정이라 지금은 {@code null}이다. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terms_id")
    private Terms terms;

    public ApproverPreference(StudentEnrollment enrollment, ApproverType preferred,
                              Instant agreedAt, Terms terms) {
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.enrollment = enrollment;
        this.preferred = preferred;
        this.agreedAt = agreedAt;
        this.terms = terms;
    }
}
