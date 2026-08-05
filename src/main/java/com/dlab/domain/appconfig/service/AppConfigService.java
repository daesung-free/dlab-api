package com.dlab.domain.appconfig.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.appconfig.entity.AppConfig;
import com.dlab.domain.appconfig.entity.Platform;
import com.dlab.domain.appconfig.repository.AppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 앱 부팅 설정 (F-4.12-3 · A-21).
 *
 * <p>앱이 켜질 때 가장 먼저 부르는 API를 뒷받침한다 — 강제 업데이트와 점검 화면 분기가
 * 여기서 갈린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigRepository appConfigRepository;

    /**
     * 부팅 시 앱이 받는 값.
     *
     * @param clientVersion 앱이 보낸 자기 버전. {@code null}이면 업데이트 판정을 하지 않는다
     */
    public record BootConfig(Platform platform, String minVersion, String latestVersion,
                             boolean updateRequired, boolean maintenance,
                             String maintenanceMessage, Instant maintenanceUntil) {
    }

    /**
     * 부팅 설정 조회.
     *
     * <p><b>설정 행이 없어도 실패시키지 않는다.</b> 앱이 켜지자마자 부르는 API라
     * 여기서 500이 나면 <b>앱이 아예 안 열린다</b> — 행이 없으면 "제약 없음"으로 답한다.
     */
    @Transactional(readOnly = true)
    public BootConfig boot(Platform platform, String clientVersion) {
        return appConfigRepository.findByPlatformAndDeletedFalse(platform)
                .map(config -> new BootConfig(
                        platform,
                        config.getMinVersion(),
                        config.getLatestVersion(),
                        config.requiresUpdate(clientVersion),
                        config.isMaintenance(),
                        config.getMaintenanceMessage(),
                        config.getMaintenanceUntil()))
                .orElseGet(() -> {
                    log.warn("앱 설정이 없습니다 — 제약 없음으로 응답합니다: platform={}", platform);
                    return new BootConfig(platform, null, null, false, false, null, null);
                });
    }

    @Transactional(readOnly = true)
    public List<AppConfig> findAll() {
        return appConfigRepository.findAllByDeletedFalseOrderByPlatform();
    }

    /** 버전 설정 변경. {@code null}은 "변경하지 않음"이다. */
    @Transactional
    public AppConfig updateVersions(Platform platform, String minVersion, String latestVersion) {
        AppConfig config = require(platform);
        config.updateVersions(minVersion, latestVersion);
        log.info("앱 버전 설정 변경: platform={}, min={}, latest={}",
                platform, config.getMinVersion(), config.getLatestVersion());
        return config;
    }

    /**
     * 점검 모드 전환.
     *
     * <p><b>운영 중 가장 위험한 스위치다</b> — 켜는 순간 전 사용자가 앱을 못 쓴다.
     * 그래서 로그를 남긴다(누가 켰는지는 `created_by`가 아니라 감사 로그가 필요한
     * 영역이지만, 현재 스키마에 수정자 컬럼이 없다).
     */
    @Transactional
    public AppConfig changeMaintenance(Platform platform, boolean maintenance,
                                       String message, Instant until) {
        AppConfig config = require(platform);
        config.changeMaintenance(maintenance, message, until);
        log.warn("앱 점검 모드 {}: platform={}, until={}", maintenance ? "ON" : "OFF", platform, until);
        return config;
    }

    private AppConfig require(Platform platform) {
        return appConfigRepository.findByPlatformAndDeletedFalse(platform)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND,
                        "앱 설정이 없습니다: " + platform));
    }
}
