package com.dlab.api.app.appconfig;

import com.dlab.domain.appconfig.service.AppNotificationSettingService;
import com.dlab.domain.appconfig.service.TermsService;

import java.time.Instant;

/** 앱 설정 응답 DTO. */
public final class SettingsResponse {

    private SettingsResponse() {
    }

    /**
     * @param agreed {@code null}이면 <b>아직 응답한 적이 없다</b> — "동의 안 함"과 구분해야
     *               가입 흐름에서 다시 물어볼지 판단할 수 있다
     */
    public record TermsStatus(Long termsId, String code, String version, String title,
                              boolean required, Boolean agreed, Instant agreedAt) {

        public static TermsStatus from(TermsService.TermsStatus status) {
            var terms = status.terms();
            return new TermsStatus(terms.getId(), terms.getCode(), terms.getVersion(),
                    terms.getTitle(), terms.isRequired(), status.agreed(), status.agreedAt());
        }
    }

    /**
     * @param mandatory 수신 거부 불가. 앱이 토글을 비활성으로 그려야 한다 —
     *                  누를 수 있는데 서버가 거절하면 사용자는 고장으로 본다
     */
    public record Preference(String event, boolean enabled, boolean mandatory) {

        public static Preference from(AppNotificationSettingService.PreferenceView view) {
            return new Preference(view.event().name(), view.enabled(), view.mandatory());
        }
    }
}
