package com.dlab.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.notification.service.RecipientAllowlist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이관 기간 수신 허용목록.
 *
 * <h2>★★ 기본은 전면 개방이다</h2>
 * 비어 있으면 <b>아무도 막지 않는다.</b> 반대로 두면(비었을 때 전부 차단) 설정을 빠뜨린
 * 환경에서 <b>알림이 조용히 전멸</b>하고, 그건 알아채기까지 오래 걸린다 —
 * 그래서 이 장치는 <b>켜야 작동한다.</b>
 *
 * <p>지키려는 것 — <b>빈 설정이 막지 않을 것</b>, <b>형식이 섞여도 걸릴 것</b>,
 * <b>번호 없음을 차단으로 읽지 않을 것</b>.
 */
class RecipientAllowlistTest {

    @Test
    @DisplayName("★★ 비어 있으면 아무도 막지 않는다 — 반대로 두면 알림이 조용히 전멸한다")
    void emptyMeansOpen() {
        RecipientAllowlist allowlist = new RecipientAllowlist("");

        assertThat(allowlist.allows("010-1234-5678")).isTrue();
        assertThat(new RecipientAllowlist(null).allows("010-1234-5678")).isTrue();
    }

    @Test
    @DisplayName("켜면 적은 번호만 통과한다")
    void onlyListedPass() {
        RecipientAllowlist allowlist = new RecipientAllowlist("010-9878-5662");

        assertThat(allowlist.allows("010-9878-5662")).isTrue();
        assertThat(allowlist.allows("010-1111-2222")).isFalse();
    }

    @Test
    @DisplayName("★ 하이픈·공백이 섞여도 걸린다 — 저장 형식이 한 가지가 아니다")
    void formatDoesNotMatter() {
        RecipientAllowlist allowlist = new RecipientAllowlist(" 01098785662 , 010-1111-2222 ");

        assertThat(allowlist.allows("010-9878-5662")).isTrue();
        assertThat(allowlist.allows("01011112222")).isTrue();
        assertThat(allowlist.allows("010 1111 2222")).isTrue();
    }

    @Test
    @DisplayName("★ 번호가 없으면 통과시킨다 — 「허용 안 됨」이 아니라 「판단할 수 없음」이다")
    void missingPhonePasses() {
        RecipientAllowlist allowlist = new RecipientAllowlist("010-9878-5662");

        assertThat(allowlist.allows(null))
                .as("번호 없음은 발송 경로가 따로 걸러낸다 — 두 곳에서 하면 사유가 헷갈린다")
                .isTrue();
        assertThat(allowlist.allows("  ")).isTrue();
    }

    @Test
    @DisplayName("★ 미배정 대역은 막힌다 — 지금 운영의 번호 전부가 그 대역이다")
    void unassignedRangeIsBlocked() {
        RecipientAllowlist allowlist = new RecipientAllowlist("010-9878-5662");

        assertThat(allowlist.allows("010-0000-0001")).isFalse();
    }
}
