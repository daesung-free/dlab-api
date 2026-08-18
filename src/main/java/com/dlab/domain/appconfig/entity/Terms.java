package com.dlab.domain.appconfig.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 약관.
 *
 * <p><b>★ 문구를 고치지 말고 버전을 올려 새 행을 추가한다.</b> 덮어쓰면 이미 동의한
 * 사람이 "무엇에 동의했는지"가 사라진다 — 동의는 법적 성격이라 그 기록이 곧 근거다.
 */
@Getter
@Entity
@Table(name = "terms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Terms extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SERVICE 이용약관 / PRIVACY 개인정보 / MARKETING 광고성 정보 수신 /
     * MEAL_THIRD_PARTY 급식업체 제3자 제공 / SCHOLARSHIP_* 장학 동의 등.
     *
     * <p><b>enum이 아닌 이유</b> — 장학 동의서만 해도 정규시즌·반수시즌·환급반 셋이고
     * 앞으로 더 늘 수 있다. 문자열이라 <b>관리자 화면에서 행만 넣으면</b> 된다.
     */
    @Column(nullable = false, length = 30)
    private String code;

    /**
     * 지점 전용 약관이면 그 지점. {@code null}이면 전 지점 공통이다.
     *
     * <p>장학 동의서 문구가 {@code DLab [지점]}으로 되어 있고 환급반은 지점명이 박혀 있어,
     * 같은 {@code code}라도 지점마다 문구가 갈릴 수 있다. <b>지점 것이 공통을 이긴다.</b>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "academy_id")
    private com.dlab.domain.user.entity.Academy academy;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 필수 약관은 동의 없이 가입이 진행되지 않는다. */
    @Column(nullable = false)
    private boolean required = true;

    /** 시행일. 미래로 두면 예약 등록이다 — 앱은 시행된 것만 받는다. */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    public Terms(String code, String version, String title, String content,
                 boolean required, Instant effectiveAt) {
        this(null, code, version, title, content, required, effectiveAt);
    }

    public Terms(com.dlab.domain.user.entity.Academy academy, String code, String version,
                 String title, String content, boolean required, Instant effectiveAt) {
        this.academy = academy;
        this.code = code;
        this.version = version;
        this.title = title;
        this.content = content;
        this.required = required;
        this.effectiveAt = effectiveAt;
    }
}
