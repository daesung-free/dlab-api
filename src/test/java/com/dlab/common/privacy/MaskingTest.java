package com.dlab.common.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    @DisplayName("연락처는 가운데를 가린다")
    void maskPhone() {
        assertThat(Masking.phone("010-1234-5678")).isEqualTo("010-****-5678");
        assertThat(Masking.phone("01012345678")).isEqualTo("010-****-5678");
    }

    @Test
    @DisplayName("번호로 보기 어려운 값은 통째로 가린다 — 원본을 흘리지 않는 쪽이 안전하다")
    void maskShortValue() {
        assertThat(Masking.phone("123")).isEqualTo("****");
    }

    @Test
    @DisplayName("이름은 가운데 글자를 가린다")
    void maskName() {
        assertThat(Masking.name("김철수")).isEqualTo("김*수");
        assertThat(Masking.name("남궁민수")).isEqualTo("남**수");
        assertThat(Masking.name("김철")).isEqualTo("김*");
        assertThat(Masking.name("김")).isEqualTo("김");
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-****-5678", "****", "김*수"})
    @DisplayName("★ 마스킹된 값을 알아본다 — 업로드에서 '변경 없음'으로 읽는 근거")
    void detectsMasked(String value) {
        assertThat(Masking.isMasked(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "김철수", "2026-0001"})
    @DisplayName("★ 실제 값을 마스킹으로 오판하지 않는다 — 오판하면 진짜 수정이 무시된다")
    void doesNotFlagRealValues(String value) {
        assertThat(Masking.isMasked(value)).isFalse();
    }

    @Test
    @DisplayName("★ 마스킹한 값은 전부 마스킹으로 판정된다 — Export/Import 왕복의 전제")
    void roundTrip() {
        // 이름은 * 하나뿐이라 판정에서 새기 쉽다. 새면 이름이 "김*수"로 저장된다.
        assertThat(Masking.isMasked(Masking.phone("010-1234-5678"))).isTrue();
        assertThat(Masking.isMasked(Masking.name("김철수"))).isTrue();
        assertThat(Masking.isMasked(Masking.name("김철"))).isTrue();
    }
}
