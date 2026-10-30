package com.dlab.api.app.appconfig;

import com.dlab.domain.appconfig.service.AppNotificationSettingService;
import com.dlab.domain.appconfig.service.TermsService;

import java.time.Instant;

/** 앱 설정 응답 DTO. */
public final class SettingsResponse {

    private SettingsResponse() {
    }

    /**
     * @param agreed    {@code null}이면 <b>아직 응답한 적이 없다</b> — "동의 안 함"과
     *                  구분해야 가입 흐름에서 다시 물어볼지 판단할 수 있다
     * @param expired   ★ <b>동의했지만 유효기간이 지났다.</b> 지금은 홍보·마케팅만
     *                  해당한다(동의일부터 2년 — 입학동의서 최종본).
     *                  <p>⚠️ {@code agreed} 는 {@code true} 로 남는다 — <b>동의한 사실
     *                  자체는 바뀌지 않았다.</b> 화면이 {@code agreed} 만 보고 「동의함」
     *                  으로 그리면 <b>사용자는 광고가 오는 줄 안다.</b>
     * @param effective ★ <b>실제로 유효한가</b> — {@code agreed && !expired}.
     *                  <p>화면은 이것을 토글 상태로 쓰면 된다. 두 값을 직접 조합하면
     *                  <b>서버와 다른 판단이 생긴다.</b>
     */
    public record TermsStatus(Long termsId, String code, String version, String title,
                              boolean required, Boolean agreed, Instant agreedAt,
                              boolean expired, boolean effective) {

        public static TermsStatus from(TermsService.TermsStatus status) {
            var terms = status.terms();
            return new TermsStatus(terms.getId(), terms.getCode(), terms.getVersion(),
                    terms.getTitle(), terms.isRequired(), status.agreed(), status.agreedAt(),
                    status.expired(), status.effective());
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
