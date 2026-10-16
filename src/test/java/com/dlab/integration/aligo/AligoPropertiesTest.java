package com.dlab.integration.aligo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 알리고 설정 판정.
 *
 * <p>지키려는 것은 <b>안전한 쪽이 기본값</b>이라는 것이다 — 설정을 빠뜨렸을 때
 * 실수로 실제 발송·과금이 일어나는 쪽보다 안 나가는 쪽이 되돌리기 쉽다.
 */
class AligoPropertiesTest {

    @Test
    @DisplayName("★ test-mode 를 비우면 테스트 모드다 — 빠뜨렸을 때 과금되지 않는 쪽으로 기운다")
    void testModeDefaultsToOn() {
        var properties = new AligoProperties("key", "user", "senderkey", "0261058090", null);
        assertThat(properties.testModeOrDefault()).isTrue();
    }

    @Test
    @DisplayName("문자는 발신프로필 키 없이도 되고, 알림톡은 그것까지 있어야 한다")
    void alimtalkNeedsSenderKey() {
        var smsOnly = new AligoProperties("key", "user", null, "0261058090", false);
        assertThat(smsOnly.configured()).isTrue();
        assertThat(smsOnly.alimtalkConfigured()).isFalse();

        var both = new AligoProperties("key", "user", "senderkey", "0261058090", false);
        assertThat(both.alimtalkConfigured()).isTrue();
    }

    @Test
    @DisplayName("발신번호가 없으면 설정된 것으로 보지 않는다 — 등록된 번호만 쓸 수 있다")
    void senderPhoneIsRequired() {
        assertThat(new AligoProperties("key", "user", "senderkey", null, false).configured())
                .isFalse();
        assertThat(new AligoProperties("key", "user", "senderkey", "  ", false).configured())
                .isFalse();
    }

    @Test
    @DisplayName("키나 아이디가 비면 설정되지 않은 것이다 — 그때는 목업이 남는다")
    void keysAreRequired() {
        assertThat(new AligoProperties("", "user", "k", "0261058090", false).configured()).isFalse();
        assertThat(new AligoProperties("key", null, "k", "0261058090", false).configured()).isFalse();
    }
}
