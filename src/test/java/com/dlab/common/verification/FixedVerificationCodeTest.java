package com.dlab.common.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 인증번호 고정 — <b>지정한 번호에만</b> 적용된다.
 *
 * <p>문자 발송이 연동 전이라 인증번호가 서버 로그로만 나간다. 테스트마다 로그를 열어야 하고
 * <b>스토어 심사 때는 그마저도 안 된다</b> — 심사자가 직접 가입해 보는데 인증번호를 받을
 * 방법이 없어 그 자리에서 막힌다.
 *
 * <p>지키려는 것 둘이다.
 * <ol>
 *   <li><b>목록에 없는 번호는 평소와 같다</b> — 전역 스위치가 아니다</li>
 *   <li><b>인증을 건너뛰지 않는다</b> — 틀린 번호를 넣으면 그대로 거부된다</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "verification.fixed-code.code=123456",
        "verification.fixed-code.phones=010-0000-1111,01000002222"
})
class FixedVerificationCodeTest {

    private static final String LISTED = "010-0000-1111";
    /** 설정에는 하이픈 없이 적혀 있다 — 표기가 달라도 같은 번호로 봐야 한다 */
    private static final String LISTED_NO_HYPHEN = "010-0000-2222";
    private static final String UNLISTED = "010-0000-9999";

    @Autowired PhoneVerificationService service;
    @Autowired StringRedisTemplate redis;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    private String savedCode(String phone) {
        return redis.opsForValue().get("verify:code:" + phone);
    }

    @Test
    @DisplayName("★ 목록에 있는 번호는 고정 인증번호를 받는다 — 심사자가 로그를 볼 필요가 없다")
    void listedPhoneGetsFixedCode() {
        service.requestCode(LISTED);

        assertThat(savedCode(LISTED)).isEqualTo("123456");
        // 그 번호로 확인까지 통과해야 한다 — 저장만 되고 확인이 막히면 의미가 없다
        assertThat(service.confirm(LISTED, "123456")).isNotBlank();
    }

    @Test
    @DisplayName("하이픈 표기가 달라도 같은 번호로 본다 — 설정과 요청의 형식이 다르다")
    void matchesRegardlessOfHyphens() {
        service.requestCode(LISTED_NO_HYPHEN);

        assertThat(savedCode(LISTED_NO_HYPHEN)).isEqualTo("123456");
    }

    @Test
    @DisplayName("★★ 목록에 없는 번호는 평소와 같다 — 전역 스위치면 운영에서 전부 뚫린다")
    void unlistedPhoneKeepsRandomCode() {
        service.requestCode(UNLISTED);

        String code = savedCode(UNLISTED);
        assertThat(code).isNotNull().hasSize(6);
        // 고정값과 같을 확률은 100만분의 1이다. 같다면 설정이 전역으로 먹은 것이다
        assertThat(code).isNotEqualTo("123456");
    }

    @Test
    @DisplayName("★ 인증을 건너뛰지 않는다 — 고정 번호라도 틀린 값은 거부된다")
    void stillRejectsWrongCode() {
        service.requestCode(LISTED);

        assertThatThrownBy(() -> service.confirm(LISTED, "000000"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("설정이 비어 있으면 아무 번호에도 적용되지 않는다")
    void emptyConfigAppliesToNobody() {
        var empty = new VerificationProperties(null, java.util.List.of(LISTED));
        assertThat(empty.appliesTo(LISTED)).isFalse();
        assertThat(empty.configured()).isFalse();

        var noPhones = new VerificationProperties("123456", java.util.List.of());
        assertThat(noPhones.appliesTo(LISTED)).isFalse();
        assertThat(noPhones.configured()).isFalse();
    }
}
