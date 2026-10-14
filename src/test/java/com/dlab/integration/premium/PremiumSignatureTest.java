package com.dlab.integration.premium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 더프리미엄 API 서명.
 *
 * <p>★★ <b>연동 정의서의 python 예제와 같은 값이 나와야 한다.</b> 서명이 틀리면 401 만
 * 오고 <b>원인이 키인지 시계인지 서명인지 구분되지 않아</b> 개발서버 앞에서 하루를 보낸다.
 * 그래서 실제 호출 전에 여기서 고정한다 — 개발서버 없이 검증할 수 있는 유일한 부분이다.
 */
class PremiumSignatureTest {

    /** 정의서 예제의 timestamp */
    private static final Clock FIXED =
            Clock.fixed(Instant.ofEpochSecond(1755590400L), ZoneOffset.UTC);

    private static final String PATH =
            "/api/students?examdt=20260417&schoolId=89001&grade=3&classNumber=1&bunho=2958";

    @Test
    @DisplayName("★★ 정의서 python 예제와 같은 서명이 나온다 — 틀리면 401 만 보고 원인을 못 찾는다")
    void matchesReferenceImplementation() {
        var headers = PremiumSignature.sign("발급받은_시크릿", "GET", PATH, FIXED);

        // python: hmac.new(SECRET, f"{ts}:GET:{path}", sha256).hexdigest()
        assertThat(headers.signature())
                .isEqualTo("8e04d1aeaa374566c37782c70397c61024d09d9d54d70b879a0a7d75e73ebde3");
        assertThat(headers.timestamp()).isEqualTo("1755590400");
    }

    @Test
    @DisplayName("★ timestamp 는 epoch 「초」다 — 밀리초를 넣으면 전부 거부된다")
    void timestampIsSecondsNotMillis() {
        var headers = PremiumSignature.sign("k", "GET", PATH, FIXED);

        // 밀리초였다면 13자리가 된다
        assertThat(headers.timestamp()).hasSize(10);
    }

    @Test
    @DisplayName("★ METHOD 는 대문자로 서명한다 — 소문자로 보내도 서명은 대문자다")
    void methodIsUpperCased() {
        assertThat(PremiumSignature.sign("k", "get", PATH, FIXED).signature())
                .isEqualTo(PremiumSignature.sign("k", "GET", PATH, FIXED).signature());
    }

    @Test
    @DisplayName("★★ 호스트를 포함해 넘기면 거절한다 — 그러면 서명만 조용히 어긋난다")
    void rejectsPathWithHost() {
        assertThatThrownBy(() -> PremiumSignature.sign("k", "GET",
                "https://api.example.co.kr/api/students?examdt=20260417", FIXED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("/api");
    }

    @Test
    @DisplayName("★ 쿼리 한 글자만 달라도 서명이 달라진다 — 그래서 빌더로 다시 조립하면 안 된다")
    void signatureDependsOnExactString() {
        String reordered =
                "/api/students?schoolId=89001&examdt=20260417&grade=3&classNumber=1&bunho=2958";

        assertThat(PremiumSignature.sign("k", "GET", reordered, FIXED).signature())
                .isNotEqualTo(PremiumSignature.sign("k", "GET", PATH, FIXED).signature());
    }

    @Test
    @DisplayName("요청마다 timestamp 가 새로 나온다 — 캐싱하면 5분 뒤 전부 401 이다")
    void timestampFollowsClock() {
        Clock later = Clock.fixed(Instant.ofEpochSecond(1755590700L), ZoneOffset.UTC);

        assertThat(PremiumSignature.sign("k", "GET", PATH, later).timestamp())
                .isEqualTo("1755590700");
    }
}
