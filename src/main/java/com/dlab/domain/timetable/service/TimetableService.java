package com.dlab.domain.timetable.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.master.entity.RoomMaster;
import com.dlab.domain.master.repository.RoomMasterRepository;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.timetable.entity.TimetableCell;
import com.dlab.domain.timetable.repository.TimetableCellRepository;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.ClassMasterRepository;
import com.dlab.domain.user.repository.TeacherRepository;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반 주간 시간표 편성 (F-C-3 「시간표·이동수업」).
 *
 * <p>화면은 있는데 <b>「학기 선택·전 학기 복사·편성 저장」이 전부 「준비 중」</b>이었다 —
 * 저장할 곳이 없었다.
 *
 * <h2>★ 축은 연도다</h2>
 * 「기수」라는 별도 축은 없다(2026-10-13 확인 — 우리 용어였고 실제로는 연도였다).
 * 그래서 화면의 「학기 선택」은 <b>연도 선택</b>이고, 복사도 연도 → 연도다.
 *
 * <h2>⚠️ 학생별 이동수업 예외는 1차 범위가 아니다</h2>
 * 여기까지가 <b>반 단위 편성</b>이다. 「이 학생만 3교시에 다른 반으로 간다」는 이 표 위에
 * <b>덮는 축</b>이라 별 테이블(학생 × 칸 × 대체 반)로 붙어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimetableService {

    private final TimetableCellRepository cellRepository;
    private final ClassMasterRepository classRepository;
    private final PeriodMasterRepository periodRepository;
    private final RoomMasterRepository roomRepository;
    private final TeacherRepository teacherRepository;

    /** 그 반의 한 주. 편성 화면이 표를 한 번에 그린다. */
    @Transactional(readOnly = true)
    public List<TimetableCell> week(AuthPrincipal me, Long classId) {
        requireClass(me, classId);
        return cellRepository.findByClass(classId);
    }

    /**
     * 그 지점·연도 전체 — 강의실 기준으로 되짚어 보는 화면(「이 시간에 어느 반이 어디에」)용.
     */
    @Transactional(readOnly = true)
    public List<TimetableCell> year(AuthPrincipal me, Long academyId, short year) {
        return cellRepository.findByYear(me.requireAcademyScope(academyId), year);
    }

    /**
     * 편성 저장.
     *
     * <h2>★ 보낸 칸만 손댄다 — 「전체 교체」가 아니다</h2>
     * 받은 목록으로 그 반의 한 주를 통째로 갈아치우면 <b>화면 버그 하나가 편성을 날린다</b>.
     * 목록을 만드는 코드가 빈 배열을 보내는 실수는 흔하고, 그때 한 학기 편성이 사라진다
     * (순찰에서 「빈 목록을 보내면 비운다」를 만들지 않은 것과 같은 판단이다).
     * 그래서 <b>빈 목록은 거절</b>하고, 보낸 칸만 넣거나 고친다.
     *
     * <h2>★ 비운 칸은 「지움」이다</h2>
     * 과목·선생님·강의실이 모두 빈 칸을 보내면 그 칸을 지운다(soft delete). 편성 화면은
     * 칸을 눌러 내용을 지우는 식으로 쓰므로, 삭제 전용 경로만 두면 화면이 칸마다 DELETE 를
     * 따로 불러야 하고 <b>저장 한 번이 통째로 트랜잭션이 되지 못한다.</b>
     * 반면 <b>빈 목록 전체</b>는 의도로 볼 수 없으니 막는다 — 「지우겠다」와 「아무것도 안 보냈다」는
     * 다르다.
     *
     * <h2>★ 비웠던 칸은 되살려 쓴다</h2>
     * 유니크 인덱스가 {@code WHERE is_deleted = FALSE} 라, 지운 행을 두고 같은 슬롯을 새로
     * 넣으면 제약은 통과하지만 <b>같은 슬롯의 행이 둘 쌓인다</b>. 비웠다가 다시 채우는 것은
     * 편성의 일상이라 기존 행을 되살린다.
     *
     * @return 그 반의 한 주(저장 후 전체) — 화면이 다시 조회하지 않아도 표를 갱신할 수 있다
     */
    @Transactional
    public List<TimetableCell> save(AuthPrincipal me, Long classId, List<CellCommand> cells) {
        ClassMaster clazz = requireClass(me, classId);
        if (cells == null || cells.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "저장할 칸이 없습니다.");
        }

        int filled = 0;
        int cleared = 0;
        for (CellCommand cell : cells) {
            PeriodMaster period = requirePeriod(clazz, cell.dayOfWeek(), cell.periodId());
            var existing = cellRepository.findSlot(classId, cell.dayOfWeek(), period.getId());

            if (TimetableCell.isBlank(cell.subject(), cell.teacherId(), cell.roomId())) {
                // 이미 없는 칸을 비우라는 것은 오류가 아니다 — 화면이 빈 격자를 통째로 보낸다
                existing.filter(c -> !c.isDeleted()).ifPresent(c -> c.markDeleted());
                if (existing.isPresent()) {
                    cleared++;
                }
                continue;
            }

            Teacher teacher = resolveTeacher(clazz, cell.teacherId());
            RoomMaster room = resolveRoom(clazz, cell.roomId());
            verifyNoConflict(clazz, cell.dayOfWeek(), period, teacher, room);

            existing.ifPresentOrElse(c -> {
                        c.restore();
                        c.fill(trim(cell.subject()), teacher, room);
                    },
                    () -> cellRepository.save(new TimetableCell(clazz, cell.dayOfWeek(), period,
                            trim(cell.subject()), teacher, room)));
            filled++;
        }

        log.info("시간표 편성 저장: academy={}, year={}, class={}, 채움={}, 비움={}, 처리자={}",
                clazz.getAcademy().getId(), clazz.getYear(), classId, filled, cleared,
                me.accountId());
        return cellRepository.findByClass(classId);
    }

    /**
     * 전년도 복사 — 「바뀐 것만 고치기」로 넘어가게 작년 편성을 올해로 옮긴다.
     *
     * <h2>★★ 멱등하다 — 두 번 눌러도 같은 결과다</h2>
     * 프론트가 <b>「루틴에서 두 번 누르면 두 배가 된 일이 있었다」</b>고 했다. 여기서는
     * 올해에 이미 있는 칸을 <b>건너뛴다</b>(덮어쓰지도 않는다) — 덮어쓰면 복사 뒤에 손으로
     * 고친 편성이 두 번째 클릭에 되돌려진다. 그래서 두 번째 호출은 전부 「이미 있음」이 된다.
     *
     * <h2>★ 반을 무엇으로 맞추는가 — ① 복사 원본({@code copiedFromId}) ② 반 이름</h2>
     * <b>반은 해마다 새로 만들어진다</b> — 작년 「고3 1반」과 올해 「고3 1반」은 다른 행이다.
     * <ol>
     *   <li><b>{@code class_master.copied_from_id} 를 먼저 본다.</b> 올해 반이 기초관리
     *       「전년도 복사」로 만들어졌으면 <b>그 값이 작년 반을 정확히 가리킨다</b> —
     *       이름이 바뀌었어도 맞는다. 추측이 아니라 기록이라 이게 1순위다</li>
     *   <li><b>없으면 반 이름으로 맞춘다.</b> 손으로 만든 반에는 원본이 없고, 이름은
     *       (지점, 연도) 안에서 유일하다({@code existsByAcademyIdAndYearAndName} 가 그 전제로
     *       중복을 막는다). 운영에서 반 이름은 해마다 같은 체계로 붙는다</li>
     * </ol>
     * <b>{@code examClassNo} 는 쓰지 않는다</b> — 비어 있는 반이 많아(채번하지 않는 반이 있다)
     * 대부분 매칭에 실패하고, 실패가 많으면 복사가 사실상 동작하지 않는 기능이 된다.
     *
     * <h2>★ 교시도 갈아끼워야 한다</h2>
     * 칸이 {@code period_master} 를 참조하고 교시 마스터는 연도별로 복사되므로, 그냥 옮기면
     * <b>올해 시간표가 작년 교시를 가리킨다</b>. (요일구분, 교시번호)로 올해 교시를 찾아 바꾼다.
     * 올해에 그 교시가 없으면 그 칸은 못 붙는다 — 건별로 알려준다.
     *
     * <h2>★ 강의실 겹침은 그 칸만 건너뛴다</h2>
     * 올해에 손으로 먼저 넣은 편성과 겹칠 수 있다. 전체를 실패시키면 <b>한 칸 때문에 복사가
     * 아예 안 되고</b>, 강의실을 비워 넣으면 말없이 홈룸으로 바뀌어 더 나쁘다.
     *
     * <p>⚠️ <b>선생님 겹침은 복사에서 보지 않는다.</b> 작년 담당이 올해 다른 반을 맡는 일이
     * 흔해서, 막으면 복사가 거의 통째로 건너뛰어진다. 선생님 배정은 복사 뒤 손으로 고치는
     * 대상이고, 그때 {@code save} 가 막는다.
     */
    @Transactional
    public CopyResult copyFromYear(AuthPrincipal me, Long academyId, short fromYear, short toYear) {
        Long scope = me.requireAcademyScope(academyId);
        if (fromYear == toYear) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "복사 대상 연도가 원본과 같습니다.");
        }

        List<ClassMaster> sources = classRepository.search(scope, fromYear);
        List<ClassMaster> targets = classRepository.search(scope, toYear);
        Map<Long, ClassMaster> sourceById = new HashMap<>();
        Map<String, ClassMaster> sourceByName = new HashMap<>();
        for (ClassMaster s : sources) {
            sourceById.put(s.getId(), s);
            sourceByName.put(s.getName(), s);
        }
        Map<Long, PeriodMaster> periodMapping = periodMapping(scope, fromYear, toYear);

        List<ClassCopyResult> rows = new ArrayList<>();
        for (ClassMaster target : targets) {
            rows.add(copyOneClass(target, sourceById, sourceByName, periodMapping));
        }

        CopyResult result = new CopyResult(fromYear, toYear, rows);
        log.info("시간표 전년도 복사: academy={}, {} → {}, 붙음={}, 이미있음={}, 미매칭반={}, 처리자={}",
                scope, fromYear, toYear, result.copied(), result.skipped(),
                result.classesWithoutSource(), me.accountId());
        return result;
    }

    private ClassCopyResult copyOneClass(ClassMaster target,
                                         Map<Long, ClassMaster> sourceById,
                                         Map<String, ClassMaster> sourceByName,
                                         Map<Long, PeriodMaster> periodMapping) {
        // ① 복사 원본(기록) → ② 반 이름(추정). 위 javadoc 참고
        ClassMaster source = target.getCopiedFromId() == null
                ? null : sourceById.get(target.getCopiedFromId());
        if (source == null) {
            source = sourceByName.get(target.getName());
        }
        if (source == null) {
            return ClassCopyResult.noSource(target);
        }

        int copied = 0;
        int skipped = 0;
        int periodMissing = 0;
        int conflicted = 0;
        for (TimetableCell origin : cellRepository.findByClass(source.getId())) {
            PeriodMaster period = periodMapping.get(origin.getPeriod().getId());
            if (period == null) {
                periodMissing++;
                continue;
            }
            var existing = cellRepository.findSlot(target.getId(), origin.getDayOfWeek(),
                    period.getId());
            // ★ 멱등 — 이미 있으면 덮어쓰지 않는다. 덮어쓰면 복사 뒤 손으로 고친 편성이
            //   두 번째 클릭에 되돌려진다
            if (existing.filter(c -> !c.isDeleted()).isPresent()) {
                skipped++;
                continue;
            }
            if (origin.getRoom() != null && !cellRepository.findRoomConflict(target.getYear(),
                    period.getId(), origin.getDayOfWeek(), origin.getRoom().getId(),
                    target.getId()).isEmpty()) {
                conflicted++;
                continue;
            }

            TimetableCell cell = existing
                    .map(c -> {
                        c.restore();
                        c.fill(origin.getSubject(), origin.getTeacher(), origin.getRoom());
                        return c;
                    })
                    .orElseGet(() -> cellRepository.save(new TimetableCell(target,
                            origin.getDayOfWeek(), period, origin.getSubject(),
                            origin.getTeacher(), origin.getRoom())));
            cell.markCopiedFrom(origin.getId());
            copied++;
        }
        return new ClassCopyResult(target.getId(), target.getName(),
                source.getId(), source.getName(), copied, skipped, periodMissing, conflicted);
    }

    /**
     * 작년 교시 id → 올해 교시. (요일구분, 교시번호)로 맞춘다.
     *
     * <p><b>시각으로 맞추지 않는다</b> — 교시 시각은 해마다 조정되므로(실제로 조정된다)
     * 시각 기준이면 거의 전부 실패한다. 번호는 그대로 쓰인다.
     */
    private Map<Long, PeriodMaster> periodMapping(Long academyId, short fromYear, short toYear) {
        Map<String, PeriodMaster> toByKey = new HashMap<>();
        for (PeriodMaster p : periodRepository.findByYear(academyId, toYear)) {
            toByKey.put(p.getDayType() + ":" + p.getPeriodNo(), p);
        }
        Map<Long, PeriodMaster> mapping = new LinkedHashMap<>();
        for (PeriodMaster from : periodRepository.findByYear(academyId, fromYear)) {
            PeriodMaster to = toByKey.get(from.getDayType() + ":" + from.getPeriodNo());
            if (to != null) {
                mapping.put(from.getId(), to);
            }
        }
        return mapping;
    }

    /**
     * 그 칸에 쓸 교시.
     *
     * <p>★ <b>요일과 교시의 요일구분이 맞는지 본다.</b> 평일 교시를 토요일 칸에 넣으면
     * <b>시간표에 적힌 시각과 실제 교시 시각이 어긋난다</b> — 토요일은 교시 구성이 따로 있다.
     *
     * <p>★ <b>연도·지점도 교시에서 확인한다.</b> 화면이 {@code periodId} 를 보내므로
     * 값만 바꿔 보내면 남의 지점·다른 연도 교시를 끼울 수 있다.
     */
    private PeriodMaster requirePeriod(ClassMaster clazz, DayOfWeek dayOfWeek, Long periodId) {
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            throw new BusinessException(ErrorCode.TIMETABLE_SUNDAY_NOT_ALLOWED);
        }
        PeriodMaster period = periodRepository.findById(periodId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.PERIOD_NOT_FOUND));
        if (!period.getAcademy().getId().equals(clazz.getAcademy().getId())
                || period.getYear() != clazz.getYear()) {
            throw new BusinessException(ErrorCode.PERIOD_NOT_FOUND);
        }
        if (period.getDayType() != DayType.of(dayOfWeek)) {
            throw new BusinessException(ErrorCode.TIMETABLE_PERIOD_DAY_MISMATCH,
                    "%s 교시를 %s 칸에 둘 수 없습니다.".formatted(period.getDayType(), dayOfWeek));
        }
        return period;
    }

    private Teacher resolveTeacher(ClassMaster clazz, Long teacherId) {
        if (teacherId == null) {
            return null;
        }
        Teacher teacher = teacherRepository.findById(teacherId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "선생님을 찾을 수 없습니다."));
        if (!teacher.getAcademy().getId().equals(clazz.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return teacher;
    }

    private RoomMaster resolveRoom(ClassMaster clazz, Long roomId) {
        if (roomId == null) {
            return null;
        }
        RoomMaster room = roomRepository.findDetailById(roomId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND,
                        "강의실을 찾을 수 없습니다."));
        if (!room.getAcademy().getId().equals(clazz.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return room;
    }

    /**
     * 같은 시간 겹침 검사.
     *
     * <p>★ <b>강의실 겹침은 DB 유니크({@code uq_timetable_room})가 최종 방어선이다.</b>
     * 여기서 먼저 보는 이유는 하나 — <b>제약 위반 메시지에는 어느 반이 먼저 쓰고 있는지가
     * 없어서</b> 담당자가 무엇을 고쳐야 할지 알 수 없다. 즉 이 검사는 안내이고,
     * 동시에 저장하는 경우를 실제로 막는 것은 DB 다.
     *
     * <p>⚠️ <b>선생님 겹침은 DB 제약으로 두지 않았다.</b> 강의실은 물리적으로 하나지만
     * 선생님 쪽은 참관·보조 같은 운영 예외가 있는지 확인되지 않았다(§4). DB 로 막으면 그
     * 예외가 나타났을 때 저장 자체가 불가능해지고 되돌리려면 마이그레이션이 필요하다.
     */
    private void verifyNoConflict(ClassMaster clazz, DayOfWeek dayOfWeek, PeriodMaster period,
                                  Teacher teacher, RoomMaster room) {
        if (room != null) {
            var conflicts = cellRepository.findRoomConflict(clazz.getYear(), period.getId(),
                    dayOfWeek, room.getId(), clazz.getId());
            if (!conflicts.isEmpty()) {
                throw new BusinessException(ErrorCode.TIMETABLE_ROOM_CONFLICT,
                        "%s %s교시 %s 강의실은 %s 이(가) 쓰고 있습니다.".formatted(
                                dayOfWeek, period.getPeriodNo(), room.getRoomNo(),
                                conflicts.get(0).getClassMaster().getName()));
            }
        }
        if (teacher != null) {
            var conflicts = cellRepository.findTeacherConflict(clazz.getYear(), period.getId(),
                    dayOfWeek, teacher.getId(), clazz.getId());
            if (!conflicts.isEmpty()) {
                throw new BusinessException(ErrorCode.TIMETABLE_TEACHER_CONFLICT,
                        "%s %s교시에 %s 선생님은 %s 수업이 있습니다.".formatted(
                                dayOfWeek, period.getPeriodNo(), teacher.getName(),
                                conflicts.get(0).getClassMaster().getName()));
            }
        }
    }

    /** 반은 화면이 보내는 값이라 <b>남의 지점 반인지 반드시 본다.</b> */
    private ClassMaster requireClass(AuthPrincipal me, Long classId) {
        ClassMaster clazz = classRepository.findDetailById(classId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
        if (!me.canAccessAcademy(clazz.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return clazz;
    }

    private static String trim(String subject) {
        return subject == null || subject.isBlank() ? null : subject.trim();
    }

    /**
     * 칸 하나.
     *
     * <p>과목·선생님·강의실이 <b>모두 비면 그 칸을 지운다</b> — 편성 화면은 칸을 눌러
     * 내용을 지우는 식으로 쓴다.
     *
     * @param roomId 그 칸에서만 쓰는 강의실(이동수업). 비우면 반 홈룸으로 떨어진다
     */
    public record CellCommand(DayOfWeek dayOfWeek, Long periodId, String subject,
                              Long teacherId, Long roomId) {
    }

    /**
     * 복사 결과 — <b>건별로 돌려준다.</b>
     *
     * <p>합계만 주면 화면이 <b>「이 반은 작년에 같은 이름이 없어 비었습니다」를 보여줄 수
     * 없다</b>. 반마다 무엇이 몇 칸 붙었고 왜 못 붙었는지 함께 내린다.
     */
    public record CopyResult(short fromYear, short toYear, List<ClassCopyResult> classes) {

        public int copied() {
            return classes.stream().mapToInt(ClassCopyResult::copied).sum();
        }

        public int skipped() {
            return classes.stream().mapToInt(ClassCopyResult::skipped).sum();
        }

        /** 작년에서 짝을 못 찾은 반 수 — 화면이 이 숫자로 안내를 띄운다. */
        public int classesWithoutSource() {
            return (int) classes.stream().filter(c -> c.sourceClassId() == null).count();
        }
    }

    /**
     * @param sourceClassId 짝지은 작년 반. {@code null}이면 <b>작년에 같은 반이 없다</b> —
     *                      반은 해마다 새로 만들어지므로 복사 원본·이름 둘로 맞춘다
     * @param skipped       올해 이미 편성돼 있어 건너뛴 칸. ★ 두 번 눌러도 같은 결과가 되는
     *                      이유가 이 칸들이다
     * @param periodMissing 올해에 그 교시가 없어 못 붙은 칸 — 교시 마스터를 먼저 복사해야 한다
     * @param conflicted    같은 시간 같은 강의실을 올해 다른 반이 이미 쓰고 있어 건너뛴 칸
     */
    public record ClassCopyResult(Long classId, String className,
                                  Long sourceClassId, String sourceClassName,
                                  int copied, int skipped, int periodMissing, int conflicted) {

        static ClassCopyResult noSource(ClassMaster target) {
            return new ClassCopyResult(target.getId(), target.getName(), null, null, 0, 0, 0, 0);
        }
    }
}
