package com.dlab.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaskingTest {

    @ParameterizedTest
    @CsvSource({
            "010-1234-5678, 010-****-5678",
            "01012345678,   010-****-5678",
            "010 1234 5678, 010-****-5678",
            "031-123-4567,  031-***-4567",
            "02-123-4567,   02-***-4567",
            "0212345678,    02-****-5678",
    })
    @DisplayName("전화번호는 뒤 4자리만 남긴다 — 서울 02는 국번이 2자리다")
    void masksPhone(String input, String expected) {
        assertThat(Masking.phone(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ 형식이 깨진 값은 더 많이 가린다 — 파싱 실패로 원본이 새는 쪽이 위험하다")
    void masksAggressivelyWhenUnparseable() {
        assertThat(Masking.phone("1234")).isEqualTo("****");
        assertThat(Masking.phone("없음")).isEqualTo("****");
    }

    @Test
    @DisplayName("빈 값은 그대로 둔다 — 미입력과 마스킹을 구분해야 한다")
    void keepsBlank() {
        assertThat(Masking.phone(null)).isNull();
        assertThat(Masking.phone("")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "김민지,   김*지",
            "남궁민수, 남**수",
            "이준,     이*",
            "김,       김",
    })
    @DisplayName("이름은 성과 끝 글자만 남긴다")
    void masksName(String input, String expected) {
        assertThat(Masking.name(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ 생년월일은 연도만 남긴다 — 월일까지 있으면 주민번호 앞자리가 복원된다")
    void masksBirthDateKeepingYear() {
        assertThat(Masking.birthDate(LocalDate.of(2007, 3, 14))).isEqualTo("2007-**-**");
        assertThat(Masking.birthDate(null)).isNull();
    }

    @Test
    @DisplayName("주소는 시/군/구까지만")
    void masksAddress() {
        assertThat(Masking.address("경기도 성남시 분당구 어딘가로 12")).isEqualTo("경기도 성남시 ***");
        assertThat(Masking.address("서울 강남구")).isEqualTo("서울 ***");
    }
}
