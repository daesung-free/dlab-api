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

    /** SERVICE 이용약관 / PRIVACY 개인정보 / MARKETING 광고성 정보 수신 등 */
    @Column(nullable = false, length = 30)
    private String code;

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
        this.code = code;
        this.version = version;
        this.title = title;
        this.content = content;
        this.required = required;
        this.effectiveAt = effectiveAt;
    }
}
