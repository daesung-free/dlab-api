package com.dlab.domain.access.service;

import com.dlab.common.crypto.SecretCipher;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.access.entity.BranchDoorCode;
import com.dlab.domain.access.repository.BranchDoorCodeRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점 도어락 번호 (긴급출입 1-5).
 *
 * <h2>흐름</h2>
 * <pre>
 * 06:59~08:00  학생이 출입문 QR 로 번호를 받아 간다 (제공 시간)
 * 08:00~       지점 관리자가 실물 도어락을 교체하고 새 번호를 여기에 넣는다
 * </pre>
 *
 * <h2>★ 교체는 덮어쓰기가 아니라 새 행이다</h2>
 * 「그때 어떤 번호였나」에 답해야 한다 — 요청 기록에는 번호를 남기지 않고 어느 이력이었는지만
 * 참조하므로, 이력을 지우면 추적이 끊긴다.
 *
 * <h2>★ 이전 번호는 다시 쓸 수 없다</h2>
 * 요구사항이 명시한 규칙이다. 돌려 쓰면 <b>지난주에 번호를 받아 간 학생이 지금도 들어온다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoorCodeService {

    /** 제공 시작. 정식 개원이 07:00 이고 그 직전부터 받는다(요구사항 1-4) */
    private static final LocalTime OPEN_FROM = LocalTime.of(6, 59);
    /** 제공 종료(이 시각부터 차단). 08:00 이후는 관리자가 번호를 교체한다 */
    private static final LocalTime OPEN_UNTIL = LocalTime.of(8, 0);

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 12;

    private final BranchDoorCodeRepository repository;
    private final AcademyRepository academyRepository;
    private final SecretCipher cipher;
    private final Clock clock;

    /**
     * 번호 등록·교체.
     *
     * <p>지점 관리자가 실물 도어락을 바꾼 뒤 그 번호를 넣는다. <b>시스템이 교체를 독촉하거나
     * 막지 않는다</b>(10/8 디랩 결정) — 다만 이전 번호와 같으면 거절한다.
     */
    @Transactional
    public BranchDoorCode register(AuthPrincipal me, Long academyId, String rawCode) {
        Long scoped = me.requireAcademyScope(academyId);
        Academy academy = academyRepository.findById(scoped)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        String code = normalize(rawCode);
        String hash = cipher.hash(code);

        if (repository.existsByAcademyIdAndCodeHash(scoped, hash)) {
            // 돌려 쓰면 지난번에 번호를 받아 간 학생이 지금도 들어온다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "전에 쓴 번호입니다. 다른 번호로 교체해 주세요.");
        }

        Instant now = Instant.now(clock);
        short year = (short) LocalDate.now(clock).getYear();
        try {
            BranchDoorCode saved = repository.save(new BranchDoorCode(
                    academy, year, cipher.encrypt(code), hash, now));
            // ★ 번호는 로그에도 남기지 않는다. 남기면 암호화한 의미가 없다
            log.info("도어락 번호 교체: academyId={}, codeId={}", scoped, saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 두 사람이 같은 번호를 동시에 넣으면 유니크 제약이 잡는다 —
            // 사전 조회만으로는 못 막는다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "전에 쓴 번호입니다. 다른 번호로 교체해 주세요.");
        }
    }

    /**
     * 학생에게 제공할 번호.
     *
     * <p>★ <b>시간 판정을 여기서 한다.</b> 호출하는 쪽(공개 페이지)에 맡기면 경로가 늘 때마다
     * 같은 판정을 다시 쓰게 되고, 한 곳에서 빠뜨리면 <b>하루 종일 번호가 열린다.</b>
     *
     * @return 평문 번호. 제공 시간이 아니거나 등록된 번호가 없으면 비어 있다
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Provided> provide(Long academyId) {
        if (!withinOpenWindow()) {
            return java.util.Optional.empty();
        }
        return repository.findCurrent(academyId)
                .map(code -> new Provided(code.getId(), cipher.decrypt(code.getCodeEncrypted())));
    }

    /** 지금이 제공 시간인가 — <b>서버 시각 기준</b>(요구사항 1-4). */
    public boolean withinOpenWindow() {
        LocalTime now = LocalTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        return !now.isBefore(OPEN_FROM) && now.isBefore(OPEN_UNTIL);
    }

    /**
     * 이력.
     *
     * <p><b>번호는 내리지 않는다.</b> 관리자는 실물 도어락을 만지는 사람이라 지금 번호를
     * 알고 있고, 화면에 평문을 띄우면 <b>어깨 너머로 새는 경로</b>가 하나 늘어난다.
     * 언제 누가 바꿨는지만 보여준다.
     */
    @Transactional(readOnly = true)
    public List<BranchDoorCode> history(AuthPrincipal me, Long academyId) {
        return repository.findHistory(me.requireAcademyScope(academyId));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<BranchDoorCode> current(AuthPrincipal me, Long academyId) {
        return repository.findCurrent(me.requireAcademyScope(academyId));
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 숫자만 남긴다.
     *
     * <p>도어락은 숫자 키패드라 공백·하이픈이 섞여 들어오면 <b>같은 번호가 다른 번호로
     * 저장</b>되고, 재사용 판정도 빠져나간다.
     */
    private static String normalize(String rawCode) {
        String digits = rawCode == null ? "" : rawCode.replaceAll("\\D", "");
        if (digits.length() < MIN_LENGTH || digits.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "도어락 번호는 숫자 %d~%d자리입니다.".formatted(MIN_LENGTH, MAX_LENGTH));
        }
        return digits;
    }

    /** @param codeId 제공한 번호의 이력 id. 요청 기록이 번호 대신 이것을 남긴다 */
    public record Provided(Long codeId, String code) {
    }
}
