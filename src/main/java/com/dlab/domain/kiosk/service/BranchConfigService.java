package com.dlab.domain.kiosk.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.kiosk.entity.BranchConfig;
import com.dlab.domain.kiosk.entity.BranchConfigAction;
import com.dlab.domain.kiosk.entity.BranchConfigHistory;
import com.dlab.domain.kiosk.repository.BranchConfigHistoryRepository;
import com.dlab.domain.kiosk.repository.BranchConfigRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점 설정 관리 (F-4.10-7).
 *
 * <h2>★ SUPER_ADMIN 전용이다</h2>
 * 다루는 값이 키오스크 시크릿 · PG 가맹점코드 · Nebula 장비 ID다. 지점 관리자에게 열면
 * <b>자기 지점 키오스크 자격증명을 스스로 재발급</b>할 수 있는데, 재발급하는 순간 그 지점
 * 키오스크가 전부 인증 실패한다. 권한 체크는 컨트롤러가 {@code @PreAuthorize}로 건다.
 *
 * <h2>★ 시크릿은 마스킹해서 내린다 — 단 한 번만 예외다</h2>
 * 재발급 <b>직후 응답에만</b> 원문을 담는다. 그때 못 받아 적으면 다시 볼 수 없고 또
 * 재발급해야 한다(= 또 키오스크가 멈춘다). 조회는 언제나 마스킹이다 —
 * {@code MD5(yyyyMMdd + secret)} 검증식 때문에 해시로 저장할 수 없어 DB에 사실상
 * 평문으로 있고, 화면에서 다시 볼 수 있게 두면 열람 경로가 하나 더 생긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BranchConfigService {

    /** 시크릿 바이트 수. MD5 입력이라 길이 자체가 안전마진이다. */
    private static final int SECRET_BYTES = 24;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BranchConfigRepository configRepository;
    private final BranchConfigHistoryRepository historyRepository;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    /** 전 지점 목록. 설정이 없는 지점도 빈 행으로 함께 내린다 — 화면이 9개를 다 그린다. */
    public List<View> list() {
        return academyRepository.findAll().stream()
                .filter(a -> !a.isDeleted())
                .map(a -> View.of(a, configRepository
                        .findByAcademyIdAndDeletedFalse(a.getId()).orElse(null)))
                .toList();
    }

    public View get(Long academyId) {
        Academy academy = requireAcademy(academyId);
        return View.of(academy, configRepository
                .findByAcademyIdAndDeletedFalse(academyId).orElse(null));
    }

    /**
     * 키오스크 자격증명 재발급.
     *
     * <p><b>원문은 이 응답에만 담긴다.</b> 다시 조회할 수 없다.
     *
     * <p><b>재발급하면 그 지점 키오스크가 즉시 인증에 실패한다</b> — 키오스크 백엔드
     * {@code stores} 테이블의 값을 같이 바꿔야 복구된다. 화면에서 이 경고를 반드시 띄울 것.
     */
    @Transactional
    public IssuedCredential issueKioskCredential(Long academyId) {
        BranchConfig config = configOrCreate(academyId);

        String clientId = "dlab-kiosk-" + academyId;
        String secret = randomSecret();
        config.issueKioskCredential(clientId, secret);

        record(academyId, BranchConfigAction.KIOSK_CREDENTIAL_ISSUED,
                "키오스크 자격증명 재발급 — 기존 값 즉시 무효");

        return new IssuedCredential(clientId, secret);
    }

    @Transactional
    public void changePgMerchantCode(Long academyId, String code) {
        configOrCreate(academyId).changePgMerchantCode(blankToNull(code));
        record(academyId, BranchConfigAction.PG_MERCHANT_CHANGED, "PG 가맹점코드 변경");
    }

    @Transactional
    public void changeNebulaDeviceId(Long academyId, String deviceId) {
        configOrCreate(academyId).changeNebulaDeviceId(blankToNull(deviceId));
        record(academyId, BranchConfigAction.NEBULA_DEVICE_CHANGED, "Nebula 장비 ID 변경");
    }

    /**
     * 지점별 정책 JSON 교체.
     *
     * <p><b>통째로 갈아끼운다</b> — 화면이 편집한 전체 맵을 보낸다. 부분 병합으로 두면
     * 항목을 지우는 방법이 없어진다.
     */
    @Transactional
    public void replacePolicy(Long academyId, Map<String, String> policy) {
        configOrCreate(academyId).replacePolicy(policy);
        record(academyId, BranchConfigAction.POLICY_CHANGED, "지점 정책 변경");
    }

    /** 변경 이력. 최신순. */
    public List<BranchConfigHistory> history(Long academyId) {
        requireAcademy(academyId);
        return historyRepository.findByAcademyIdAndDeletedFalseOrderByIdDesc(academyId);
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 설정 행이 없으면 만든다.
     *
     * <p>지점을 만들 때 설정 행까지 같이 만들지 않았기 때문에, 처음 값을 넣는 시점에
     * 생긴다. 화면에서 "설정 없음"과 "빈 설정"을 구분할 필요가 없다.
     */
    private BranchConfig configOrCreate(Long academyId) {
        requireAcademy(academyId);
        return configRepository.findByAcademyIdAndDeletedFalse(academyId)
                .orElseGet(() -> configRepository.save(new BranchConfig(academyId)));
    }

    private Academy requireAcademy(Long academyId) {
        return academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    /** {@code created_by}는 {@code SecurityAuditorAware}가 채운다 — 여기서 넣지 않는다. */
    private void record(Long academyId, BranchConfigAction action, String detail) {
        historyRepository.save(new BranchConfigHistory(academyId,
                (short) LocalDate.now(clock).getYear(), action, detail));
    }

    private String randomSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ── 응답 ──────────────────────────────────────────────────

    /**
     * 지점 설정 한 줄.
     *
     * <p><b>비밀값은 전부 마스킹이다.</b> 화면은 "설정돼 있는가"만 알면 되고,
     * 실제 값은 재발급 응답에서 한 번만 볼 수 있다.
     */
    public record View(Long academyId, String academyName, String acadCd,
                       String kioskClientId, String kioskSecretMasked,
                       String pgMerchantCodeMasked, String nebulaDeviceId,
                       Map<String, String> policy) {

        static View of(Academy academy, BranchConfig config) {
            if (config == null) {
                return new View(academy.getId(), academy.getName(), academy.getAcadCd(),
                        null, null, null, null, Map.of());
            }
            return new View(academy.getId(), academy.getName(), academy.getAcadCd(),
                    config.getKioskClientId(),
                    mask(config.getKioskSecret()),
                    mask(config.getPgMerchantCode()),
                    config.getNebulaDeviceId(),
                    config.getConfigJson());
        }

        /**
         * 앞 4자만 남긴다.
         *
         * <p><b>길이를 그대로 노출하지 않는다</b> — 남은 자리를 실제 길이만큼 채우면
         * 시크릿 길이가 새어나간다. 4자보다 짧으면 전부 가린다.
         */
        private static String mask(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            if (value.length() <= 4) {
                return "****";
            }
            return value.substring(0, 4) + "****";
        }
    }

    /**
     * 재발급 결과.
     *
     * <p><b>{@code secret}은 여기서만 볼 수 있다.</b> 조회 API는 마스킹만 내린다.
     */
    public record IssuedCredential(String clientId, String secret) {
    }
}
