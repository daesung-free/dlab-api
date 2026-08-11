package com.dlab.domain.kiosk.entity;

import com.dlab.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 지점별 외부 연동 설정 (요구사항 F-4.10-7). 지점당 1행.
 *
 * <p>물리 키오스크가 여러 대여도 1행이면 충분하다 — 기기는 우리에게 직접 인증하지 않고
 * 키오스크 백엔드가 지점 단위로 한 번 인증한다.
 *
 * <p><b>{@code kioskSecret}은 사실상 평문 비밀값이다.</b>
 * 검증식이 {@code MD5(yyyyMMdd + secret)}라 원문을 다시 계산해야 해서 해시로 저장할 수 없다.
 * 저장소 암호화(RDS at-rest)를 전제로 하고, 화면에는 마스킹해서 노출한다.
 * 값을 로그·커밋에 남기지 말 것(CLAUDE.md §8).
 */
@Getter
@Entity
@Table(name = "branch_config")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchConfig extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    /** 키오스크가 {@code /auth/token}에 제시하는 식별자. */
    @Column(name = "kiosk_client_id", length = 100)
    private String kioskClientId;

    @Column(name = "kiosk_secret", length = 200)
    private String kioskSecret;

    /** 디랩 자체 명의 PG 가맹점코드. 급식·등록비 공통. */
    @Column(name = "pg_merchant_code", length = 100)
    private String pgMerchantCode;

    /** Zyxel Nebula 제어 대상. 단말/정책 중 무엇인지 미확정(E-1)이라 문자열로 둔다. */
    @Column(name = "nebula_device_id", length = 100)
    private String nebulaDeviceId;

    /**
     * 지점별 정책값.
     *
     * <p><b>스키마가 확정되지 않은 값의 임시 수용소로 쓰지 말 것.</b> 여기 들어간 값은
     * 타입 검증도 인덱스도 없어서, 오타가 나도 기동 시점에는 아무 일이 없고 그 값을 읽는
     * 화면에서만 조용히 비어 보인다. 지속적으로 쓰는 값이면 컬럼으로 승격한다.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, String> configJson = new LinkedHashMap<>();

    public BranchConfig(Long academyId) {
        this.academyId = academyId;
    }

    public void issueKioskCredential(String clientId, String secret) {
        this.kioskClientId = clientId;
        this.kioskSecret = secret;
    }

    public boolean hasKioskCredential() {
        return kioskClientId != null && kioskSecret != null;
    }

    public void changePgMerchantCode(String pgMerchantCode) {
        this.pgMerchantCode = pgMerchantCode;
    }

    public void changeNebulaDeviceId(String nebulaDeviceId) {
        this.nebulaDeviceId = nebulaDeviceId;
    }

    /** 통째로 갈아끼운다 — 화면이 편집한 전체 맵을 그대로 보낸다. */
    public void replacePolicy(Map<String, String> policy) {
        this.configJson = policy == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policy);
    }
}
