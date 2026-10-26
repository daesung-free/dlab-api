package com.dlab.domain.user.entity;

import com.dlab.common.entity.BaseEntity;
import com.dlab.domain.audit.AuditLabel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 학생 가입 반려 이력 (A-2 · F-4.12-1).
 *
 * <h2>★ 사유를 계정에 한 칸으로 두지 않는다</h2>
 * 재신청이 여러 번 가능하므로 마지막 사유만 남기면 <b>몇 번 반려됐는지에 답할 수 없다.</b>
 * 약관 동의를 행으로 쌓는 것과 같은 판단이다.
 *
 * <h2>★ 전달은 앱 화면에서 한다</h2>
 * 승인 전에는 로그인이 막혀 알림톡 말고는 길이 없어 보이지만 둘이 있다 —
 * <b>로그인 시도</b>(비밀번호가 맞아야 응답을 받으므로 본인이다)와 <b>재가입 시도</b>다.
 * ⚠️ 무인증 「가입 상태 조회」는 만들지 않는다 — 전화번호만으로 <b>학생 고유ID 가 새고</b>,
 * 그 ID 를 아는 사람은 누구나 남의 자녀로 연결할 수 있다(§3).
 */
@Getter
@Entity
@Table(name = "signup_rejection")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignupRejection extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Short year;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "academy_id", nullable = false)
    private Academy academy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private StudentEnrollment enrollment;

    /** ★ 필수다. 비워 두면 학생이 무엇을 고쳐야 하는지 알 수 없다. */
    @Column(nullable = false, length = 500)
    @AuditLabel("반려사유")
    private String reason;

    /** 재신청으로 해소된 시각. {@code null} 이면 아직 반려 상태다. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public SignupRejection(StudentEnrollment enrollment, Account account, String reason) {
        this.enrollment = enrollment;
        this.academy = enrollment.getAcademy();
        this.year = enrollment.getYear();
        this.account = account;
        this.reason = reason;
    }

    /** 재신청으로 해소한다. ★ 행은 지우지 않는다 — 반려 횟수가 근거로 남아야 한다. */
    public void resolve(Instant at) {
        if (resolvedAt == null) {
            this.resolvedAt = at;
        }
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }
}
