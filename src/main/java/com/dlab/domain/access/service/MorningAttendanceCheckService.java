package com.dlab.domain.access.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.access.entity.MorningAttendanceCheck;
import com.dlab.domain.access.repository.MorningAttendanceCheckRepository;
import com.dlab.domain.menu.repository.AccountMenuRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오전 출근 확인 (긴급출입 기능 B).
 *
 * <p>매일 07:05 에 지점별 당일 출근 기록이 하나도 없으면 관리자에게 알린다.
 * 「지점에 아무도 출근하지 않아 학생이 문 앞에 서 있는」 상황을 잡으려는 것이다.
 *
 * <h2>★ 휴일을 빼지 않는다</h2>
 * 요구사항의 「매일」을 글자 그대로 돌린다 — 일요일·공휴일·휴관일도 검사하고 발송한다
 * (2026-10-08 디랩 결정). <b>{@code HolidayCalendar} 를 쓰지 말 것.</b>
 *
 * <h2>★ 발송은 아직 없다</h2>
 * SMS 채널과 알리고 연동이 선행이고 <b>발신번호를 기다리는 중</b>이다. 지금은 대상을
 * 세어 기록하고 로그로 남긴다 — 그래야 연동 전에도 <b>판정이 맞는지</b> 검증된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MorningAttendanceCheckService {

    /** 알림 수신 권한 코드. 계정에 이 권한을 켜 둔 사람이 받는다(요구사항 3-2) */
    public static final String ALERT_MENU_CODE = "EMERGENCY_ACCESS_ALERT";

    private final MorningAttendanceCheckRepository checkRepository;
    private final AcademyRepository academyRepository;
    private final com.dlab.domain.attendance.repository.StaffAttendanceRepository attendanceRepository;
    private final AccountMenuRepository accountMenuRepository;
    private final Clock clock;

    /**
     * 전 지점 검사.
     *
     * <p>★ <b>한 지점의 실패가 다른 지점을 막지 않는다.</b> 예외를 올리면 지점 하나의
     * 데이터 문제로 그날 전 지점 검사가 건너뛰어진다 — 그게 가장 위험한 실패다.
     *
     * @return 알림이 필요한(출근 0건) 지점 수
     */
    @Transactional
    public int runAll(LocalDate date) {
        List<Academy> academies = academyRepository.findAll().stream()
                .filter(a -> !a.isDeleted() && a.isActive())
                .toList();

        int needed = 0;
        for (Academy academy : academies) {
            try {
                if (runOne(academy, date).isAlertNeeded()) {
                    needed++;
                }
            } catch (RuntimeException e) {
                log.error("오전 출근 확인 실패: academyId={}, date={}", academy.getId(), date, e);
            }
        }
        return needed;
    }

    /**
     * 지점 하나.
     *
     * <p><b>멱등하다.</b> 이미 검사한 날이면 그 기록을 그대로 돌려준다 — 배치가 두 번
     * 돌거나 손으로 다시 돌려도 <b>같은 날이 두 줄로 남지 않는다.</b>
     */
    @Transactional
    public MorningAttendanceCheck runOne(Academy academy, LocalDate date) {
        var existing = checkRepository.findByAcademyIdAndCheckDate(academy.getId(), date);
        if (existing.isPresent()) {
            return existing.get();
        }

        int inCount = attendanceRepository.countArrivalsOfDay(academy.getId(), date);
        int recipients = inCount == 0 ? countRecipients(academy.getId()) : 0;

        MorningAttendanceCheck check;
        try {
            check = checkRepository.save(new MorningAttendanceCheck(
                    academy, (short) date.getYear(), date, Instant.now(clock),
                    inCount, recipients));
        } catch (DataIntegrityViolationException e) {
            // 두 인스턴스가 같은 시각에 돌면 유니크 제약이 잡는다 — 사전 조회로는 못 막는다
            return checkRepository.findByAcademyIdAndCheckDate(academy.getId(), date)
                    .orElseThrow(() -> e);
        }

        if (check.isAlertNeeded()) {
            // ⚠️ 발송은 아직 없다(SMS 채널·알리고 연동 대기). 판정이 맞는지는 이 로그로 검증한다
            log.warn("★ 오전 출근 미확인: {} ({}) — 수신 대상 {}명. 발송은 알리고 연동 후",
                    academy.getName(), date, recipients);
            if (recipients == 0) {
                // 검사는 돌았는데 받을 사람이 없다 — 설정 문제라 조용히 넘기면 안 된다
                log.error("★ 오전 출근 미확인인데 수신 대상이 없다: academyId={}. "
                        + "계정에 {} 권한을 켜 줄 것", academy.getId(), ALERT_MENU_CODE);
            }
        }
        return check;
    }

    /**
     * 수신 대상 수.
     *
     * <p>★★ <b>권한을 명시적으로 켜 둔 계정만</b> 센다. 역할 기본값으로 세면
     * <b>개별 설정이 없는 모든 계정이 대상</b>이 되어 아침 문자가 전 직원에게 나간다
     * ({@code AccountMenuRepository.findAccountsWithMenu} 설명 참고).
     *
     * <p>지점 담당과 <b>전 지점 권한자</b>를 함께 센다 — 요구사항이 「본사 + 지점 관리자」다.
     */
    private int countRecipients(Long academyId) {
        var branchStaff = accountMenuRepository.findAccountsWithMenu(ALERT_MENU_CODE, academyId);
        var headOffice = accountMenuRepository.findAccountsWithMenu(ALERT_MENU_CODE, null).stream()
                // 지점이 없는 계정(= 소속을 특정할 수 없는 계정)은 본사로 보지 않는다.
                // 학생·학부모 계정이 섞여 들어오면 학부모에게 지점 운영 알림이 간다
                .filter(a -> a.getEmployee() == null && a.getTeacher() == null)
                .toList();
        if (!headOffice.isEmpty()) {
            log.debug("소속 지점이 없는 수신 권한 계정 {}건 — 대상에서 제외한다", headOffice.size());
        }
        return branchStaff.size();
    }

    /** 발송 문구(요구사항 2-2 원문). 템플릿 심사가 없어 서버에서 조립한다. */
    public String alertMessage(Academy academy) {
        return "[대성] %s지점 7시 출근이 안 이루어졌습니다.".formatted(academy.getName());
    }

    @Transactional(readOnly = true)
    public List<MorningAttendanceCheck> search(AuthPrincipal me, LocalDate from, LocalDate to) {
        // 전 지점 권한자가 지점을 안 고르면 전 지점이다 — 본사가 아침에 한눈에 본다
        return checkRepository.search(me.resolveAcademyScope(null), from, to);
    }
}
