package com.dlab.api.admin.patrol;

import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.patrol.entity.PatrolMark;
import com.dlab.domain.patrol.entity.PatrolRecord;
import com.dlab.domain.patrol.service.PatrolService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 순찰 관리 (2026-10-13 디랩 피드백 1-3 — 신규 기능).
 *
 * <p>지금은 <b>학생 정보를 종이에 인쇄해 체크한 뒤 DSA 에 다시 입력</b>한다 — 같은 일을
 * 두 번 한다. 태블릿에서 좌석을 눌러 바로 넣는다.
 *
 * <p>★ <b>담임도 쓴다.</b> 순찰은 사감·담임이 도는 업무라 지점 관리자로 제한하면 쓸 수 없다.
 */
@Tag(name = "관리자 · 순찰")
@RestController
@RequestMapping("/api/v1/admin/patrols")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
public class AdminPatrolController {

    private final PatrolService patrolService;

    /**
     * 그날 그 회차 기록.
     *
     * <p>날짜를 비우면 <b>오늘</b>, 회차를 비우면 <b>그날 전부</b>다 — 순찰은 당일 업무라
     * 화면이 날짜를 안 보내는 것이 기본 흐름이다.
     */
    @GetMapping
    public ApiResponse<List<PatrolRow>> records(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Short round,
            // ★ 기록에 남은 좌석으로 거른다 — 현재 배정으로 거르면 그 사이 자리를 옮긴
            //   학생이 빠지거나 끼어들어 화면의 좌석표와 어긋난다
            @RequestParam(required = false) Long studyAreaId) {

        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(
                patrolService.records(me, academyId, date, round, studyAreaId).stream()
                .map(r -> PatrolRow.of(r, raw)).toList());
    }

    /**
     * 순찰 기록 저장.
     *
     * <p>★ <b>한 번에 여러 건을 받는다.</b> 순찰은 좌석을 연달아 누르는 업무라, 한 건씩
     * 보내면 네트워크가 느린 태블릿에서 <b>누른 것이 빠진 채 넘어간다.</b>
     *
     * <p>★ <b>같은 회차를 다시 찍으면 덮어쓴다</b> — 순찰을 다시 돌 수 있다.
     */
    @PostMapping
    public ApiResponse<List<PatrolRow>> record(@CurrentAccount AuthPrincipal me,
                                               @Valid @RequestBody PatrolRequest request) {
        boolean raw = PersonalDataPolicy.canViewRaw(me);
        return ApiResponse.success(patrolService.record(me, request.academyId(), request.date(),
                        request.round(), request.toCommands()).stream()
                .map(r -> PatrolRow.of(r, raw)).toList());
    }

    /**
     * 그 회차를 비운다 — 잘못 찍은 회차를 통째로 지운다.
     *
     * <p>★★ <b>「빈 목록을 저장하면 비운다」로 만들지 않았다.</b> 그러면 <b>화면 버그 하나가
     * 회차를 조용히 날린다</b> — 목록을 만드는 코드가 빈 배열을 보내는 실수는 흔하고, 그때
     * 사감이 한 시간 돌며 찍은 것이 사라진다. <b>지우는 것은 지우겠다고 말해야</b> 한다.
     *
     * <p>soft delete 다 — 「그 회차에 무엇이 있었나」를 나중에 확인할 수 있어야 한다.
     *
     * @return 지운 건수
     */
    @DeleteMapping("/rounds/{round}")
    public ApiResponse<ClearedCount> clearRound(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Short round,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        return ApiResponse.success(
                new ClearedCount(patrolService.clearRound(me, academyId, date, round)));
    }

    public record ClearedCount(int cleared) {
    }

    /** 상태 값 목록 — 화면 팔레트가 쓴다. 문구를 화면에 중복 정의하지 않게 서버가 내린다. */
    @GetMapping("/marks")
    public ApiResponse<List<MarkView>> marks() {
        return ApiResponse.success(java.util.Arrays.stream(PatrolMark.values())
                .map(m -> new MarkView(m.name(), m.label(), m.away(), m.group()))
                .toList());
    }

    /**
     * @param date  비우면 오늘
     * @param round 1~5. <b>회차를 합치지 않는다</b> — 같은 학생이 회차마다 다를 수 있다
     */
    public record PatrolRequest(Long academyId,
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @NotNull Short round,
                                @NotEmpty(message = "찍을 좌석을 골라 주세요.")
                                List<MarkRequest> marks) {

        List<PatrolService.MarkCommand> toCommands() {
            return marks.stream()
                    .map(m -> new PatrolService.MarkCommand(m.enrollmentId(), m.seatCd(), m.mark()))
                    .toList();
        }
    }

    /** @param seatCd 찍은 시점의 좌석번호. 학생 목록 탭에서 찍으면 비어 있어도 된다 */
    public record MarkRequest(@NotNull Long enrollmentId, String seatCd,
                              @NotNull PatrolMark mark) {
    }

    /**
     * @param away   자리를 비운 상태인가. <b>「없다」와 「졸고 있다」는 다르다</b> —
     *               졸음은 자리에 있는 것이다
     * @param masked 이름이 가려졌는지 — 화면이 「이름이 잘못 저장됐다」로 오인하지 않게
     */
    public record PatrolRow(Long id, LocalDate date, short round,
                            Long enrollmentId, String studentNo, String studentName,
                            String seatCd, PatrolMark mark, String markLabel, boolean away,
                            boolean masked) {

        static PatrolRow of(PatrolRecord r, boolean raw) {
            String name = r.getEnrollment().getStudent().getName();
            return new PatrolRow(r.getId(), r.getPatrolDate(), r.getRoundNo(),
                    r.getEnrollment().getId(), r.getEnrollment().getStudentNo(),
                    raw ? name : Masking.name(name),
                    r.getSeatCd(), r.getMark(), r.getMark().label(), r.getMark().away(),
                    !raw);
        }
    }

    /**
     * @param group 사감이 할 일 기준 묶음 — 화면이 색을 가르는 데 쓴다.
     *              ★ <b>화면이 코드로 묶지 않게 서버가 내린다</b> — 화면에서 묶으면 상태가
     *              늘 때마다 화면을 고쳐야 하고, 잊으면 새 상태가 아무 묶음에도 안 들어간다
     */
    public record MarkView(String code, String label, boolean away, PatrolMark.Group group) {
    }
}
