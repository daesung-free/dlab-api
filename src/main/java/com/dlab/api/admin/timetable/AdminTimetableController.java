package com.dlab.api.admin.timetable;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.master.entity.RoomMaster;
import com.dlab.domain.timetable.entity.TimetableCell;
import com.dlab.domain.timetable.service.TimetableService;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 반 주간 시간표 편성 (F-C-3 「시간표·이동수업」).
 *
 * <p>화면은 있는데 <b>「학기 선택·전 학기 복사·편성 저장」이 전부 「준비 중」</b>이었다 —
 * 저장할 곳이 없었다.
 *
 * <p>★ <b>경로는 {@code /timetables} 다.</b> {@code /schedules} 는 <b>정기일정</b>이고
 * 이미 다른 기능으로 붙어 있다 — 이름만 보고 거기에 얹으면 두 기능이 같은 경로를 다툰다.
 *
 * <p>★ <b>축은 연도다.</b> 「기수」라는 별도 축은 없다(2026-10-13 확인) — 화면의
 * 「학기 선택」은 연도 선택이고 복사도 연도 → 연도다.
 */
@Tag(name = "관리자 · 시간표 편성 (F-C-3)")
@RestController
@RequestMapping("/api/v1/admin/timetables")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminTimetableController {

    private final TimetableService timetableService;

    /**
     * 그 반의 한 주.
     *
     * <p>칸마다 <b>실제 적용 강의실</b>({@code effectiveRoomNo})을 함께 내린다 —
     * 셀 강의실이 비면 반 홈룸이다. 이 판정을 화면에 맡기면 화면마다 다르게 떨어진다.
     *
     * <h2>★★ 담임도 읽는다 — 이게 격자의 <b>본체</b>다</h2>
     * 교시·강의실·교사만 열고 이것을 막으면 <b>축은 그려지고 내용이 비는</b> 더 헷갈리는
     * 모양이 된다. 담임에게 `timetable` 메뉴를 열어 둔 이상 함께 열려야 한다.
     *
     * <p>⚠️ <b>편성(`PUT`)·복사(`POST`)는 그대로 막힌다.</b> 담임이 자기 반을 보는 것과
     * 지점 시간표를 짜는 것은 다른 일이다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
    @GetMapping("/classes/{classId}")
    public ApiResponse<List<TimetableCellRow>> week(@CurrentAccount AuthPrincipal me,
                                                    @PathVariable Long classId) {
        return ApiResponse.success(timetableService.week(me, classId).stream()
                .map(TimetableCellRow::of).toList());
    }

    /**
     * 그 지점·연도 전체 — 「이 시간에 어느 반이 어디에」를 되짚는 화면용.
     *
     * <p>★ <b>담임도 읽는다</b> — 아래 주 단위 조회와 같은 이유다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
    @GetMapping
    public ApiResponse<List<TimetableCellRow>> year(@CurrentAccount AuthPrincipal me,
                                                    @RequestParam(required = false) Long academyId,
                                                    @RequestParam short year) {
        return ApiResponse.success(timetableService.year(me, academyId, year).stream()
                .map(TimetableCellRow::of).toList());
    }

    /**
     * 편성 저장.
     *
     * <p>★ <b>보낸 칸만 손댄다.</b> 받은 목록으로 한 주를 통째로 갈아치우면 화면 버그 하나가
     * 편성을 날린다 — 목록을 만드는 코드가 빈 배열을 보내는 실수는 흔하다. 그래서
     * <b>빈 목록은 거절</b>한다.
     *
     * <p>★ <b>내용이 모두 빈 칸은 「지움」</b>이다. 편성 화면은 칸을 눌러 내용을 지우는 식으로
     * 쓰므로, 삭제를 별 경로로만 두면 저장 한 번이 통째로 트랜잭션이 되지 못한다.
     *
     * <p>★ <b>같은 시간 같은 강의실은 거절된다.</b> DB 유니크가 최종 방어선이고 여기서
     * 먼저 보는 것은 <b>어느 반이 쓰고 있는지</b>를 알려주기 위해서다.
     */
    @PutMapping("/classes/{classId}")
    public ApiResponse<List<TimetableCellRow>> save(@CurrentAccount AuthPrincipal me,
                                                    @PathVariable Long classId,
                                                    @Valid @RequestBody TimetableSaveRequest request) {
        return ApiResponse.success(
                timetableService.save(me, classId, request.toCommands()).stream()
                        .map(TimetableCellRow::of).toList());
    }

    /**
     * 전년도 복사 — 「바뀐 것만 고치기」로 넘어가게 작년 편성을 올해로 옮긴다.
     *
     * <p>★ <b>멱등하다.</b> 두 번 눌러도 같은 결과다 — 올해에 이미 있는 칸은 건너뛴다
     * (덮어쓰면 복사 뒤 손으로 고친 편성이 되돌려진다).
     *
     * <p>★ <b>결과를 건별로 돌려준다.</b> 합계만 주면 화면이
     * 「이 반은 작년에 같은 이름이 없어 비었습니다」를 보여줄 수 없다.
     */
    @PostMapping("/copy")
    public ApiResponse<TimetableCopyResponse> copy(@CurrentAccount AuthPrincipal me,
                                                   @Valid @RequestBody TimetableCopyRequest request) {
        return ApiResponse.success(TimetableCopyResponse.of(timetableService.copyFromYear(
                me, request.academyId(), request.fromYear(), request.toYear())));
    }

    /** @param cells 격자 전체를 보내도 된다 — 내용이 빈 칸은 지움으로 읽는다 */
    @Schema(name = "TimetableSaveRequest")
    public record TimetableSaveRequest(
            @NotEmpty(message = "저장할 칸이 없습니다.") List<TimetableCellRequest> cells) {

        List<TimetableService.CellCommand> toCommands() {
            return cells.stream()
                    .map(c -> new TimetableService.CellCommand(c.dayOfWeek(), c.periodId(),
                            c.subject(), c.teacherId(), c.roomId()))
                    .toList();
        }
    }

    /**
     * @param periodId 교시 마스터. ★ 번호가 아니라 <b>마스터 참조</b>다 — 번호만 들면
     *                 교시 시각을 고쳤을 때 시간표만 옛 시각으로 남는다
     * @param roomId   그 칸에서만 쓰는 강의실(이동수업). <b>비우면 반 홈룸으로 떨어진다</b>
     */
    @Schema(name = "TimetableCellRequest")
    public record TimetableCellRequest(@NotNull DayOfWeek dayOfWeek, @NotNull Long periodId,
                                       String subject, Long teacherId, Long roomId) {
    }

    @Schema(name = "TimetableCopyRequest")
    public record TimetableCopyRequest(Long academyId,
                                       @NotNull Short fromYear, @NotNull Short toYear) {
    }

    /**
     * @param roomNo          그 칸에 지정된 강의실. 비어 있으면 이동수업이 아니라는 뜻이다
     * @param effectiveRoomNo ★ <b>실제 적용 강의실</b> — 셀이 비면 반 홈룸이다.
     *                        서버가 한 곳에서 정해 내린다
     * @param homeroomNo      반 홈룸 — 화면이 「홈룸이라서 비었다」와 「홈룸도 없다」를
     *                        구분해 보여줄 수 있어야 한다
     */
    @Schema(name = "TimetableCellRow")
    public record TimetableCellRow(Long id, Long classId, String className,
                                   DayOfWeek dayOfWeek,
                                   Long periodId, short periodNo, String periodName,
                                   LocalTime startTime, LocalTime endTime,
                                   String subject, Long teacherId, String teacherName,
                                   Long roomId, String roomNo,
                                   Long effectiveRoomId, String effectiveRoomNo,
                                   Long homeroomId, String homeroomNo) {

        static TimetableCellRow of(TimetableCell c) {
            RoomMaster effective = c.effectiveRoom();
            RoomMaster homeroom = c.getClassMaster().getRoom();
            return new TimetableCellRow(c.getId(),
                    c.getClassMaster().getId(), c.getClassMaster().getName(),
                    c.getDayOfWeek(),
                    c.getPeriod().getId(), c.getPeriod().getPeriodNo(), c.getPeriod().getName(),
                    c.getPeriod().getStartTime(), c.getPeriod().getEndTime(),
                    c.getSubject(),
                    c.getTeacher() == null ? null : c.getTeacher().getId(),
                    c.getTeacher() == null ? null : c.getTeacher().getName(),
                    c.getRoom() == null ? null : c.getRoom().getId(),
                    c.getRoom() == null ? null : c.getRoom().getRoomNo(),
                    effective == null ? null : effective.getId(),
                    effective == null ? null : effective.getRoomNo(),
                    homeroom == null ? null : homeroom.getId(),
                    homeroom == null ? null : homeroom.getRoomNo());
        }
    }

    /**
     * @param classesWithoutSource 작년에서 짝을 못 찾은 반 수 — 화면이 이 숫자로 안내를 띄운다
     * @param classes              반별 결과. ★ 합계만 주면 화면이 「이 반은 작년에 같은 이름이
     *                             없어 비었습니다」를 보여줄 수 없다
     */
    @Schema(name = "TimetableCopyResponse")
    public record TimetableCopyResponse(short fromYear, short toYear,
                                        int copied, int skipped, int classesWithoutSource,
                                        List<TimetableService.ClassCopyResult> classes) {

        static TimetableCopyResponse of(TimetableService.CopyResult r) {
            return new TimetableCopyResponse(r.fromYear(), r.toYear(), r.copied(), r.skipped(),
                    r.classesWithoutSource(), r.classes());
        }
    }
}
