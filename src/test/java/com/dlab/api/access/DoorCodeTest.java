package com.dlab.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.crypto.SecretCipher;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.access.service.DoorCodeService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점 도어락 번호 (긴급출입 1-5).
 *
 * <p>지키려는 것 넷이다.
 * <ol>
 *   <li><b>이전에 쓴 번호를 다시 쓸 수 없다</b> — 돌려 쓰면 지난번에 번호를 받아 간 학생이 들어온다</li>
 *   <li><b>평문으로 저장하지 않는다</b> — DB 가 새면 전 지점 출입문이 열린다</li>
 *   <li><b>해시가 비밀키를 섞는다</b> — 네 자리 숫자의 단순 해시는 사실상 평문이다</li>
 *   <li><b>교체는 덮어쓰기가 아니라 새 행</b> — 「그때 어떤 번호였나」에 답해야 한다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties =
        // 32바이트 Base64. 운영은 환경변수로 넣고 커밋하지 않는다
        "security.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DoorCodeTest {

    @Autowired DoorCodeService doorCodeService;
    @Autowired SecretCipher cipher;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    AuthPrincipal branchAdmin;

    @BeforeEach
    void setUp() {
        academy = new Academy("DC01", "도어락테스트지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();

        branchAdmin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    @Test
    @DisplayName("번호를 등록하면 이력이 쌓이고 가장 최근 것이 지금 번호다")
    void registersAndKeepsHistory() {
        doorCodeService.register(branchAdmin, academy.getId(), "1234");
        doorCodeService.register(branchAdmin, academy.getId(), "5678");
        em.flush();

        var history = doorCodeService.history(branchAdmin, academy.getId());
        assertThat(history).hasSize(2);
        // 덮어쓰지 않는다 — 그때 어떤 번호였나에 답해야 한다
        assertThat(doorCodeService.current(branchAdmin, academy.getId()))
                .get().extracting(c -> cipher.decrypt(c.getCodeEncrypted()))
                .isEqualTo("5678");
    }

    @Test
    @DisplayName("★★ 전에 쓴 번호는 거절된다 — 돌려 쓰면 지난번 학생이 지금도 들어온다")
    void rejectsReuse() {
        doorCodeService.register(branchAdmin, academy.getId(), "1234");
        doorCodeService.register(branchAdmin, academy.getId(), "5678");
        em.flush();

        assertThatThrownBy(() ->
                doorCodeService.register(branchAdmin, academy.getId(), "1234"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("전에 쓴 번호");
    }

    @Test
    @DisplayName("공백·하이픈은 흘려보낸다 — 같은 번호가 다른 번호로 저장되면 재사용 판정이 빠져나간다")
    void normalizesInput() {
        doorCodeService.register(branchAdmin, academy.getId(), "1234");
        em.flush();

        assertThatThrownBy(() ->
                doorCodeService.register(branchAdmin, academy.getId(), "12-34"))
                .hasMessageContaining("전에 쓴 번호");
    }

    @Test
    @DisplayName("★ 평문으로 저장하지 않는다 — 같은 번호도 매번 다른 암호문이 된다")
    void storesEncrypted() {
        var saved = doorCodeService.register(branchAdmin, academy.getId(), "1234");
        em.flush();

        assertThat(saved.getCodeEncrypted()).doesNotContain("1234");
        assertThat(cipher.decrypt(saved.getCodeEncrypted())).isEqualTo("1234");

        // 다른 지점에 같은 번호를 넣어 보면 암호문이 다르다(IV 가 다르다).
        // 같으면 "같은 번호를 쓴다"는 사실이 DB 만 보고도 드러난다
        Academy other = new Academy("DC02", "다른지점", LocalTime.of(9, 0));
        em.persist(other);
        em.flush();
        AuthPrincipal otherAdmin = AuthPrincipal.of(2L, "EMPLOYEE", other.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        var otherSaved = doorCodeService.register(otherAdmin, other.getId(), "1234");
        assertThat(otherSaved.getCodeEncrypted()).isNotEqualTo(saved.getCodeEncrypted());
    }

    @Test
    @DisplayName("★★ 해시는 비밀키를 섞는다 — 네 자리 숫자의 단순 해시는 사실상 평문이다")
    void hashIsKeyed() {
        String plainSha = sha256("1234");
        assertThat(cipher.hash("1234")).isNotEqualTo(plainSha);
        // 같은 값이면 항상 같아야 비교에 쓸 수 있다
        assertThat(cipher.hash("1234")).isEqualTo(cipher.hash("1234"));
    }

    @Test
    @DisplayName("다른 지점은 같은 번호를 쓸 수 있다 — 재사용 금지는 지점 안에서만이다")
    void otherBranchCanUseSameCode() {
        doorCodeService.register(branchAdmin, academy.getId(), "1234");

        Academy other = new Academy("DC03", "또다른지점", LocalTime.of(9, 0));
        em.persist(other);
        em.flush();
        AuthPrincipal otherAdmin = AuthPrincipal.of(3L, "EMPLOYEE", other.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        assertThat(doorCodeService.register(otherAdmin, other.getId(), "1234")).isNotNull();
    }

    @Test
    @DisplayName("숫자 자릿수를 벗어나면 거절한다")
    void rejectsBadLength() {
        assertThatThrownBy(() -> doorCodeService.register(branchAdmin, academy.getId(), "12"))
                .hasMessageContaining("4~12자리");
        assertThatThrownBy(() -> doorCodeService.register(branchAdmin, academy.getId(), "abcd"))
                .hasMessageContaining("4~12자리");
    }

    @Test
    @DisplayName("★ 지점 관리자는 남의 지점 번호를 바꿀 수 없다")
    void cannotTouchOtherBranch() {
        Academy other = new Academy("DC04", "남의지점", LocalTime.of(9, 0));
        em.persist(other);
        em.flush();

        assertThatThrownBy(() ->
                doorCodeService.register(branchAdmin, other.getId(), "9999"))
                .isInstanceOf(BusinessException.class);
    }

    private static String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.Base64.getEncoder().encodeToString(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
