package com.dlab.domain.access.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.privacy.Masking;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.access.entity.DoorCodeRequest;
import com.dlab.domain.access.entity.DoorCodeRequestResult;
import com.dlab.domain.access.repository.DoorCodeRequestRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 긴급 출입 번호 제공 (긴급출입 1-6 · 공개 경로).
 *
 * <p>출입문에 붙은 QR 을 찍으면 열리는 페이지다. <b>로그인이 없다</b> — 문 앞에 서 있는
 * 학생이 앱을 깔고 로그인할 상황이 아니라서다. 그래서 신원 확인이 <b>휴대폰 번호 하나</b>다.
 *
 * <h2>★★ 학생에게는 거절 이유를 알려주지 않는다</h2>
 * 어느 조건에 걸렸는지 알려주면 <b>번호를 두드려 보는 사람에게 힌트</b>가 된다
 * (「그 번호는 재원생이 아닙니다」는 곧 「그 번호는 우리 학생입니다」의 반대 정보다).
 * 화면은 전부 같은 문구이고, <b>갈라서 보는 것은 관리자 내역</b>이다.
 *
 * <h2>★ 거절도 전부 기록한다</h2>
 * 거절 건을 빼면 <b>대입 시도가 기록에서 사라진다.</b> 막은 시도({@code RATE_LIMITED})까지
 * 남겨야 공격이 있었다는 사실이 보인다 — 막기만 하고 기록하지 않으면 아무도 모른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoorCodeRequestService {

    /** 학생에게 보이는 거절 문구. <b>이유를 가리지 않는다</b> — 전부 같은 문장이다 */
    public static final String DENIED_MESSAGE =
            "확인할 수 없습니다. 데스크 또는 담당 선생님께 문의해 주세요.";

    private final DoorCodeRequestRepository requestRepository;
    private final DoorCodeService doorCodeService;
    private final DoorAccessRateLimiter rateLimiter;
    private final AcademyRepository academyRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /**
     * 번호 요청 (공개).
     *
     * <p>★ <b>어떤 경로로 끝나도 기록을 남긴다.</b> 그래서 반환값이 아니라 저장이 먼저다.
     *
     * @param acadCd 출입문 QR 이 담은 지점코드(31~48)
     * @param rawPhone 학생이 입력한 휴대폰 번호. 하이픈이 섞여 와도 된다
     * @param requesterIp 공개 경로라 주체가 없어 IP 를 남긴다
     */
    @Transactional
    public Outcome request(String acadCd, String rawPhone, String requesterIp) {
        Academy academy = academyRepository.findByAcadCdAndDeletedFalse(acadCd)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND,
                        "지점을 찾을 수 없습니다. QR 을 다시 찍어 주세요."));

        String digits = digitsOf(rawPhone);
        if (digits.length() < 10 || digits.length() > 11) {
            // 기록하지 않는다 — 번호 형식 오타는 사건이 아니고, 남기면 오타가 내역을 덮는다
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "휴대폰 번호를 정확히 입력해 주세요.");
        }

        Instant now = Instant.now(clock);
        short year = (short) LocalDate.now(clock.withZone(com.dlab.common.config.TimeConfig.KST))
                .getYear();

        // ★ 제한을 먼저 본다. 조회를 먼저 하면 막힌 요청도 DB 를 때린다
        if (!rateLimiter.tryAcquire(requesterIp, digits)) {
            log.warn("긴급 출입 시도 제한: academy={}, ip={}", acadCd, requesterIp);
            return denied(record(academy, year, DoorCodeRequestResult.RATE_LIMITED,
                    null, digits, null, now, requesterIp));
        }

        // 시간 → 등록 여부 → 학생 순으로 본다.
        // ★ 시간을 먼저 보는 이유: 시간 밖이면 학생을 조회할 이유가 없고,
        //   밤새 번호를 두드려도 DB 조회가 일어나지 않는다
        if (!doorCodeService.withinOpenWindow()) {
            return denied(record(academy, year, DoorCodeRequestResult.OUTSIDE_WINDOW,
                    null, digits, null, now, requesterIp));
        }

        Lookup lookup = lookUp(academy.getId(), digits);
        if (lookup.rejection() != null) {
            return denied(record(academy, year, lookup.rejection(),
                    lookup.student(), digits, null, now, requesterIp));
        }

        var provided = doorCodeService.provide(academy.getId());
        if (provided.isEmpty()) {
            // 시간은 맞으니 등록된 번호가 없다는 뜻이다.
            // ★ 조치 대상이 학생이 아니라 지점 관리자다
            log.warn("긴급 출입 요청인데 등록된 도어락 번호가 없다: academy={}", acadCd);
            return denied(record(academy, year, DoorCodeRequestResult.NO_CODE_REGISTERED,
                    lookup.student(), digits, null, now, requesterIp));
        }

        record(academy, year, DoorCodeRequestResult.PROVIDED, lookup.student(), digits,
                provided.get().codeId(), now, requesterIp);
        return new Outcome(true, provided.get().code(), null);
    }

    /**
     * 번호로 학생을 찾고 제공 가능한지 함께 판단한다.
     *
     * <p>조회와 판정을 한 번에 하는 이유는 <b>같은 쿼리를 두 번 돌리지 않기 위해서다</b> —
     * 찾기와 거절 판정을 나누면 「중복인가」를 다시 물어보게 된다.
     */
    private Lookup lookUp(Long academyId, String digits) {
        List<Long> current = enrollmentRepository.findCurrentIdsByPhoneDigits(academyId, digits);

        if (current.size() > 1) {
            // 형제가 같은 보호자 번호를 적어 둔 경우가 있다. 어느 학생인지 정해지지 않는다
            return new Lookup(null, DoorCodeRequestResult.DUPLICATE_PHONE);
        }
        if (current.size() == 1) {
            return new Lookup(enrollmentRepository.findById(current.get(0)).orElse(null), null);
        }

        // 현재 건이 없으면 종료분으로 한 번 더 본다 —
        // 「퇴원생이 받으려 했다」와 「그런 번호 없음」은 다른 사건이라 담당자에게 달리 보여야 한다
        List<Long> any = enrollmentRepository.findAnyIdsByPhoneDigits(academyId, digits);
        if (any.isEmpty()) {
            return new Lookup(null, DoorCodeRequestResult.NOT_FOUND);
        }
        return new Lookup(enrollmentRepository.findById(any.get(0)).orElse(null),
                DoorCodeRequestResult.NOT_ENROLLED);
    }

    /** @param rejection 거절 사유. 제공해도 되면 {@code null} */
    private record Lookup(StudentEnrollment student, DoorCodeRequestResult rejection) {
    }

    private DoorCodeRequest record(Academy academy, short year, DoorCodeRequestResult result,
                                   StudentEnrollment student, String digits, Long codeId,
                                   Instant now, String requesterIp) {
        var doorCode = codeId == null ? null
                : doorCodeService.referenceOf(codeId);
        return requestRepository.save(new DoorCodeRequest(academy, year, result, student,
                Masking.phone(digits), doorCode, now, requesterIp));
    }

    private static Outcome denied(DoorCodeRequest saved) {
        return new Outcome(false, null, saved.getResult());
    }

    private static String digitsOf(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    // ── 관리자 내역 (관리자 웹 10번) ──────────────────────────

    /**
     * 요청 내역.
     *
     * <p>지점을 비우면 전 지점이다 — 본사가 아침에 한눈에 본다. 지점 관리자는
     * {@code requireAcademyScope} 가 자기 지점으로 묶는다.
     */
    @Transactional(readOnly = true)
    public List<DoorCodeRequest> history(AuthPrincipal me, Long academyId,
                                         LocalDate from, LocalDate to) {
        var zone = com.dlab.common.config.TimeConfig.KST;
        return requestRepository.search(me.requireAcademyScope(academyId),
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * @param code 제공한 번호. 거절이면 {@code null}
     * @param result 거절 사유. <b>학생 화면에 쓰지 말 것</b> — 관리자 내역 전용이다
     */
    public record Outcome(boolean provided, String code, DoorCodeRequestResult result) {

        /** 학생에게 보일 문구 — 이유를 가리지 않는다 */
        public String message() {
            return provided ? null : DENIED_MESSAGE;
        }
    }
}
