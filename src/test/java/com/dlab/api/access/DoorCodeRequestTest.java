package com.dlab.api.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.access.entity.DoorCodeRequestResult;
import com.dlab.domain.access.service.DoorCodeRequestService;
import com.dlab.domain.access.service.DoorCodeService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 긴급 출입 번호 제공 (긴급출입 1-6).
 *
 * <p>지키려는 것 다섯이다.
 * <ol>
 *   <li><b>★★ 학생에게 거절 이유를 알려주지 않는다</b> — 알려주면 대입에 힌트가 된다</li>
 *   <li><b>거절도 전부 기록한다</b> — 빼면 두드려 본 시도가 기록에서 사라진다</li>
 *   <li><b>하이픈이 섞여도 같은 번호로 찾는다</b> — 못 찾으면 제대로 넣은 학생이 문 앞에서 거절된다</li>
 *   <li><b>★ 번호 중복·퇴원은 「없음」과 다르게 기록한다</b> — 담당자 조치가 다르다</li>
 *   <li><b>★ 요청 기록에 번호 평문이 없다</b> — 남기면 암호화한 의미가 사라진다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties =
        "security.secret-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class DoorCodeRequestTest {

    @Autowired DoorCodeRequestService requestService;
    @Autowired DoorCodeService doorCodeService;
    @Autowired com.dlab.domain.access.repository.DoorCodeRequestRepository requestRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    /** 제공 시간 판정만 고정한다 — 시계를 통째로 바꾸면 다른 판정까지 흔들린다 */
    @MockitoBean DoorCodeService.OpenWindow openWindow;

    Academy academy;
    AuthPrincipal admin;
    short year;

    /**
     * ★ <b>테스트마다 다른 IP 를 쓴다.</b> 시도 제한은 Redis 에 있어 <b>트랜잭션 롤백으로
     * 지워지지 않고</b> 창(75분)이 다음 실행까지 남는다. 고정값을 쓰면 뒤 테스트들이
     * 제한에 걸려 엉뚱하게 실패한다 — 실제로 한 번 그렇게 깨졌다.
     */
    String ip;

    /**
     * ★ <b>번호도 실행마다 다르게 쓴다.</b> 시도 제한은 IP 와 <b>번호</b> 두 축으로 세는데
     * 번호 쪽 창도 Redis 에 75분 남는다 — 고정 번호를 쓰면 같은 테스트를 몇 번 돌린 뒤
     * 제한에 걸려 실패한다. 뒷자리만 바꿔 쓴다.
     */
    String phoneTail;

    @BeforeEach
    void setUp() {
        year = (short) java.time.LocalDate.now().getYear();
        academy = new Academy("DR01", "긴급출입지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        doorCodeService.register(admin, academy.getId(), "2468");
        em.flush();

        ip = randomIp();
        phoneTail = "%04d".formatted((int) (Math.random() * 10000));
        Mockito.when(openWindow.isOpen()).thenReturn(true);
    }

    /** 그 지점 재원생. 연락처는 하이픈을 섞어 저장한다(실제 데이터가 그렇다) */
    private StudentEnrollment enroll(String code, String phone, boolean current) {
        Student person = new Student(code, "학생" + code, phone);
        em.persist(person);
        StudentEnrollment enrollment = new StudentEnrollment(person, academy, year,
                code, null, GradeType.N_SU);
        if (!current) {
            enrollment.markWithdrawn(java.time.LocalDate.now());
        }
        em.persist(enrollment);
        em.flush();
        return enrollment;
    }

    @Test
    @DisplayName("재원생이면 번호를 받는다 — 하이픈이 섞인 저장값도 같은 번호로 찾는다")
    void providesToEnrolledStudent() {
        enroll("DR-1", phone("1111"), true);

        var outcome = requestService.request(academy.getAcadCd(), digits("1111"), ip);

        assertThat(outcome.provided()).isTrue();
        assertThat(outcome.code()).isEqualTo("2468");
        assertThat(outcome.message()).isNull();
    }

    @Test
    @DisplayName("★★ 거절 이유는 학생에게 가지 않는다 — 조건별로 전부 같은 문구다")
    void deniedMessageIsUniform() {
        enroll("DR-2", phone("2222"), true);

        var notFound = requestService.request(academy.getAcadCd(), "01099998888", ip);
        Mockito.when(openWindow.isOpen()).thenReturn(false);
        var outsideWindow = requestService.request(academy.getAcadCd(), digits("2222"), ip);

        // 사유는 갈려서 기록되지만
        assertThat(notFound.result()).isEqualTo(DoorCodeRequestResult.NOT_FOUND);
        assertThat(outsideWindow.result()).isEqualTo(DoorCodeRequestResult.OUTSIDE_WINDOW);
        // 학생이 보는 문구는 같다 — 다르면 어느 조건에 걸렸는지 역산된다
        assertThat(notFound.message()).isEqualTo(outsideWindow.message());
        assertThat(notFound.code()).isNull();
        assertThat(outsideWindow.code()).isNull();
    }

    @Test
    @DisplayName("★ 거절도 기록된다 — 빼면 번호를 두드려 본 시도가 기록에서 사라진다")
    void recordsRejections() {
        requestService.request(academy.getAcadCd(), "01099998888", ip);
        em.flush();

        var rows = requestService.history(admin, academy.getId(),
                java.time.LocalDate.now(), java.time.LocalDate.now());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getResult()).isEqualTo(DoorCodeRequestResult.NOT_FOUND);
        assertThat(rows.get(0).getRequesterIp()).isEqualTo(ip);
    }

    @Test
    @DisplayName("★ 같은 번호를 쓰는 학생이 둘이면 제공하지 않는다 — 어느 학생인지 정해지지 않는다")
    void rejectsDuplicatePhone() {
        enroll("DR-3", phone("3333"), true);
        enroll("DR-4", phone("3333"), true);

        var outcome = requestService.request(academy.getAcadCd(), digits("3333"), ip);

        assertThat(outcome.provided()).isFalse();
        assertThat(outcome.result()).isEqualTo(DoorCodeRequestResult.DUPLICATE_PHONE);
    }

    @Test
    @DisplayName("★ 퇴원생은 「없음」이 아니라 「재원생 아님」으로 기록된다 — 조치가 다르다")
    void distinguishesFormerStudent() {
        enroll("DR-5", phone("5555"), false);

        var outcome = requestService.request(academy.getAcadCd(), digits("5555"), ip);

        assertThat(outcome.provided()).isFalse();
        assertThat(outcome.result()).isEqualTo(DoorCodeRequestResult.NOT_ENROLLED);
    }

    @Test
    @DisplayName("★★ 등록된 번호가 없으면 「지점 조치 필요」로 기록된다 — 학생 잘못이 아니다")
    void flagsBranchActionWhenNoCode() {
        Academy bare = new Academy("DR09", "번호없는지점", LocalTime.of(9, 0));
        em.persist(bare);
        Student person = new Student("DR-9", "학생DR-9", phone("7777"));
        em.persist(person);
        em.persist(new StudentEnrollment(person, bare, year, "DR-9", null, GradeType.N_SU));
        em.flush();

        var outcome = requestService.request(bare.getAcadCd(), digits("7777"), ip);

        assertThat(outcome.result()).isEqualTo(DoorCodeRequestResult.NO_CODE_REGISTERED);
        // 화면이 색을 달리해 묶는 근거다
        assertThat(outcome.result().needsBranchAction()).isTrue();
        assertThat(DoorCodeRequestResult.NOT_FOUND.needsBranchAction()).isFalse();
    }

    @Test
    @DisplayName("제공 시간 밖에는 학생을 조회하지 않고 거절한다")
    void rejectsOutsideWindow() {
        enroll("DR-6", phone("6666"), true);
        Mockito.when(openWindow.isOpen()).thenReturn(false);

        var outcome = requestService.request(academy.getAcadCd(), digits("6666"), ip);

        assertThat(outcome.provided()).isFalse();
        assertThat(outcome.result()).isEqualTo(DoorCodeRequestResult.OUTSIDE_WINDOW);
    }

    @Test
    @DisplayName("★ 요청 기록에 번호 평문이 없다 — 남기면 암호화한 의미가 사라진다")
    void recordKeepsNoPlainCode() {
        enroll("DR-7", phone("8888"), true);
        requestService.request(academy.getAcadCd(), digits("8888"), ip);
        em.flush();

        var row = requestService.history(admin, academy.getId(),
                java.time.LocalDate.now(), java.time.LocalDate.now()).get(0);

        // 번호 대신 이력 행을 가리킨다 — "그때 어떤 번호였나"는 그걸로 추적한다
        assertThat(row.getDoorCode()).isNotNull();
        // 입력 번호도 마스킹돼 있다 — 평문이면 요청 기록이 곧 연락처 목록이 된다.
        // 가운데가 가려지고 뒷 4자리는 남는다(010-****-5678) — 담당자가 학생을 특정할 수 있어야
        // 조치가 되므로 통째로 가리지 않는 것이 맞다
        assertThat(row.getPhoneMasked()).doesNotContain("8888");
        assertThat(row.getPhoneMasked()).contains("*").endsWith(phoneTail);
    }

    @Test
    @DisplayName("번호 형식이 틀리면 기록하지 않고 오류다 — 오타가 내역을 덮으면 안 된다")
    void rejectsMalformedPhoneWithoutRecording() {
        assertThatThrownBy(() -> requestService.request(academy.getAcadCd(), "0101234", ip))
                .isInstanceOf(BusinessException.class);

        assertThat(requestRepository.count()).isZero();
    }

    @Test
    @DisplayName("★★ 시도 제한에 걸리면 거절되고 그 사실이 기록된다 — 막기만 하면 아무도 모른다")
    void limitsAttempts() {
        enroll("DR-8", phone("4444"), true);
        // IP 제한(10)을 넘기도록 번호를 바꿔 가며 두드린다 = 실제 공격 모양
        DoorCodeRequestService.Outcome last = null;
        for (int i = 0; i < 12; i++) {
            last = requestService.request(academy.getAcadCd(),
                    "010%07d".formatted(1000000 + i), ip);
        }
        em.flush();

        assertThat(last.provided()).isFalse();
        assertThat(last.result()).isEqualTo(DoorCodeRequestResult.RATE_LIMITED);

        // ★ 막은 시도도 내역에 남아야 공격이 있었다는 사실이 보인다
        var rows = requestService.history(admin, academy.getId(),
                java.time.LocalDate.now(), java.time.LocalDate.now());
        assertThat(rows).anyMatch(r -> r.getResult() == DoorCodeRequestResult.RATE_LIMITED);

        // 제한에 걸려도 학생에게는 같은 문구다 — 다르면 우회 간격을 역산할 수 있다
        assertThat(last.message()).isEqualTo(DoorCodeRequestService.DENIED_MESSAGE);
    }

    @Test
    @DisplayName("같은 학생이 여러 번 누르는 것은 막지 않는다 — 문이 안 열려 다급한 상황이다")
    void allowsRepeatedTriesFromSameStudent() {
        enroll("DR-10", phone("1212"), true);
        // 같은 번호로 연속 5회. IP 제한(10) 안이라 전부 통과해야 한다
        for (int i = 0; i < 5; i++) {
            assertThat(requestService.request(academy.getAcadCd(), digits("1212"), ip)
                    .provided()).isTrue();
        }
    }

    @Test
    @DisplayName("★ IP 는 거절 건에만 내린다 — 제공 건은 누구인지 이미 알아서 더할 것이 없다")
    void exposesIpOnlyForRejections() {
        enroll("DR-11", phone("9090"), true);
        requestService.request(academy.getAcadCd(), digits("9090"), ip);   // 제공
        requestService.request(academy.getAcadCd(), "01000000000", ip);    // 거절
        em.flush();

        var rows = requestService.history(admin, academy.getId(),
                java.time.LocalDate.now(), java.time.LocalDate.now()).stream()
                .map(com.dlab.api.admin.access.AdminDoorRequestController.RequestRow::from)
                .toList();

        var provided = rows.stream()
                .filter(r -> r.result() == DoorCodeRequestResult.PROVIDED).findFirst().orElseThrow();
        var rejected = rows.stream()
                .filter(r -> r.result() == DoorCodeRequestResult.NOT_FOUND).findFirst().orElseThrow();

        // 공격 판별에 필요한 쪽에만 있다 — 같은 IP 가 다른 번호를 두드린 것이 보여야 한다
        assertThat(rejected.requesterIp()).isEqualTo(ip);
        assertThat(provided.requesterIp()).isNull();
    }

    @Test
    @DisplayName("없는 지점코드는 오류다 — QR 을 잘못 읽은 경우다")
    void rejectsUnknownAcademy() {
        assertThatThrownBy(() -> requestService.request("99", digits("1111"), ip))
                .isInstanceOf(BusinessException.class);
    }

    /** 저장용 — 하이픈을 섞는다(실제 데이터가 그렇다) */
    private String phone(String mid) {
        return "010-" + mid + "-" + phoneTail;
    }

    /** 입력용 — 숫자만. 같은 번호를 다른 모양으로 넣어도 찾아야 한다 */
    private String digits(String mid) {
        return "010" + mid + phoneTail;
    }

    /**
     * ★ <b>공간을 넓게 쓴다.</b> 한 옥텟(250가지)만 무작위로 하면 테스트끼리 IP 가 겹쳐,
     * 시도 제한을 일부러 넘기는 테스트가 태운 횟수가 <b>엉뚱한 테스트에 걸린다</b>
     * (실제로 그렇게 깨졌다). Redis 라 롤백도 되지 않아 창(75분)이 다음 실행까지 남는다.
     */
    private static String randomIp() {
        var rnd = java.util.concurrent.ThreadLocalRandom.current();
        return "10.%d.%d.%d".formatted(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(1, 255));
    }
}
