package com.dlab.domain.facility.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.SeatStyle;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.facility.repository.SeatMasterRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 등록·수정·삭제.
 *
 * <h2>★ 격자 등록이 기본 경로다</h2>
 * 한 구역이 수십 석이라 한 칸씩 등록하게 하면 <b>실무에서 안 쓴다</b> — 좌표를 사람이
 * 하나씩 채워 넣어야 하고, 한 번 틀리면 배치도가 어긋난 채로 남는다. 행·열과 시작 번호만
 * 받아 좌표·코드를 서버가 만든다. 통로처럼 비는 칸은 {@code skips}로 빼고, <b>번호는 빈
 * 칸을 건너뛰고 이어진다</b>(실제 좌석표가 그렇게 붙어 있다).
 *
 * <h2>★ 좌석번호는 구역 안에서만 유일하다 — 본관과 별관은 겹쳐도 된다</h2>
 * 동탄2관처럼 본관과 좌석번호가 같은 별관이 실제로 있고, 클라이언트 요구가 <b>같은 번호를
 * 쓰되 구분되는 것</b>이다. 그래서 우리 쪽 유일성은 구역 단위로 두고, 키오스크에 내릴
 * {@code kioskSeatCd}를 따로 저장해 <b>지점 단위 유일성은 그쪽이 진다</b>.
 *
 * <h2>★ 충돌을 DB 제약에 맡기지 않고 미리 모아서 알려준다</h2>
 * 격자로 40석을 넣다가 중간에 터지면 어디까지 들어갔는지 알 수 없고, 제약 위반은 화면에
 * 이유가 안 보인다. <b>검사는 두 겹</b>이다 — 구역 안 좌석번호와, 지점 안 키오스크 번호.
 * 뒤쪽이 필요한 이유는 별관 1번의 변환 결과(1001)가 <b>본관에 실재하는 1001번</b>과
 * 겹칠 수 있어서다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatMasterAdminService {

    /** 한 번에 만들 수 있는 좌석 수. 오타(행 100 × 열 100)로 만 건이 들어가는 것을 막는다. */
    private static final int MAX_GRID_SIZE = 500;

    private final SeatMasterRepository seatMasterRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final StudyAreaAdminService studyAreaAdminService;
    private final KioskCodeTranslator kioskCodeTranslator;

    public List<SeatMaster> list(AuthPrincipal me, Long studyAreaId) {
        studyAreaAdminService.load(me, studyAreaId);
        return seatMasterRepository.findByStudyAreaId(studyAreaId);
    }

    /** 좌석 단건 등록. 배치도에서 한 자리만 끼워 넣을 때 쓴다. */
    @Transactional
    public SeatMaster create(AuthPrincipal me, Long studyAreaId, String seatCd, String seatNm,
                             int xPos, int yPos, SeatStyle seatStyle) {
        StudyArea area = studyAreaAdminService.load(me, studyAreaId);
        List<String> codes = List.of(seatCd);

        Map<String, SeatMaster> existing = existingByCd(area, codes);
        rejectIfOccupied(area, existing, codes);

        Map<String, String> kioskCds = kioskCodes(area, codes);
        rejectIfKioskCodeTaken(area, existing, kioskCds);

        return persist(area, existing, seatCd, kioskCds.get(seatCd),
                seatNm == null ? seatCd : seatNm, xPos, yPos,
                seatStyle == null ? SeatStyle.CLASSROOM : seatStyle);
    }

    /**
     * 격자 일괄 등록.
     *
     * <p><b>전부-아니면-전무다.</b> 하나라도 코드가 겹치면 겹친 코드를 <b>전부 모아</b>
     * 알려주고 아무것도 만들지 않는다 — 절반만 들어간 배치도는 지우고 다시 만드는 것보다
     * 손이 더 간다.
     */
    @Transactional
    public List<SeatMaster> createGrid(AuthPrincipal me, SeatGridSpec spec) {
        StudyArea area = studyAreaAdminService.load(me, spec.studyAreaId());

        List<PlannedSeat> planned = plan(spec);
        List<String> codes = planned.stream().map(PlannedSeat::seatCd).toList();

        Map<String, SeatMaster> existing = existingByCd(area, codes);
        rejectIfOccupied(area, existing, codes);

        Map<String, String> kioskCds = kioskCodes(area, codes);
        rejectIfKioskCodeTaken(area, existing, kioskCds);

        List<SeatMaster> created = new ArrayList<>(planned.size());
        for (PlannedSeat p : planned) {
            created.add(persist(area, existing, p.seatCd(), kioskCds.get(p.seatCd()),
                    p.seatCd(), p.xPos(), p.yPos(), p.seatStyle()));
        }
        return created;
    }

    /**
     * 이미 있는 좌석에 형태를 <b>번호 범위로</b> 입힌다.
     *
     * <h2>★ 왜 격자 등록과 별도로 두는가</h2>
     * 좌석을 지웠다 다시 만들면 <b>배정 이력이 그 행을 참조하고 있어</b> 끊긴다. 형태는
     * 키오스크에 내려가지 않으니 기존 행을 고치는 쪽이 안전하다.
     *
     * <p>번호는 좌석번호의 <b>끝에 붙은 숫자</b>로 읽는다({@code A-01} → 1). 숫자가 없는
     * 좌석은 범위에 들 수 없어 건드리지 않는다.
     *
     * @param defaultStyle 범위 밖 좌석의 형태. {@code null}이면 <b>그대로 둔다</b>
     * @return 실제로 값이 바뀐 좌석 수
     */
    @Transactional
    public int applyStyles(AuthPrincipal me, Long studyAreaId, List<StyleRange> ranges,
                           SeatStyle defaultStyle) {
        StudyArea area = studyAreaAdminService.load(me, studyAreaId);
        rejectIfRangesOverlap(ranges);

        int changed = 0;
        for (SeatMaster seat : seatMasterRepository.findByStudyAreaId(area.getId())) {
            Integer number = trailingNumber(seat.getSeatCd());
            SeatStyle style = number == null ? defaultStyle : styleOf(number, ranges, defaultStyle);
            if (style == null || style == seat.getSeatStyle()) {
                continue;
            }
            seat.changeStyle(style);
            changed++;
        }
        return changed;
    }

    /** 이름·좌표·형태 수정. {@code seatCd}는 대상이 아니다(키오스크가 이 코드로 좌석을 찾는다). */
    @Transactional
    public SeatMaster update(AuthPrincipal me, Long seatId, String seatNm, Integer xPos,
                             Integer yPos, SeatStyle seatStyle) {
        SeatMaster seat = load(me, seatId);
        seat.update(seatNm, xPos, yPos, seatStyle);
        return seat;
    }

    /**
     * 좌석 삭제(soft).
     *
     * <p><b>물리 삭제하지 않는다</b> — {@code seat_assignment}가 이 행을 참조하므로 지우면
     * 과거 배정 이력이 끊긴다. <b>배정 중인 좌석은 거부한다</b>: 지워도 배정 행은 살아 있어
     * "없는 좌석에 앉아 있는 학생"이 남는다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long seatId) {
        SeatMaster seat = load(me, seatId);
        if (seatAssignmentRepository.findActiveBySeatId(seatId).isPresent()) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_OCCUPIED,
                    "배정된 좌석은 삭제할 수 없습니다. 배정을 먼저 해제하세요.");
        }
        seat.markDeleted();
    }

    /**
     * 좌석 일괄 삭제 — <b>전부-아니면-전무</b>.
     *
     * <h2>왜 필요한가</h2>
     * 화면의 「전체 초기화」가 단건 삭제를 좌석 수만큼 불렀다. 지점에 <b>92석</b>까지
     * 있어서, 중간에 하나가 배정돼 있으면 <b>절반만 지워진 배치도</b>가 남는다 —
     * 어디까지 지워졌는지 화면에서 알 방법이 없다.
     *
     * <h2>★ 배정된 좌석이 섞이면 전부 거부한다</h2>
     * 일부만 지우고 성공으로 돌려주면 <b>「지운 줄 알았는데 남아 있는」</b> 상태가 된다.
     * 대신 <b>배정된 좌석을 전부 모아</b> 알려준다 — 하나씩 알려주면 고쳐 올릴 때마다
     * 한 건씩만 발견하게 된다(격자 등록의 번호 충돌과 같은 판단).
     *
     * @param seatIds 지울 좌석. <b>비우면 그 구역 전체</b>다
     * @return 지운 건수
     */
    @Transactional
    public int deleteAll(AuthPrincipal me, Long studyAreaId, List<Long> seatIds) {
        StudyArea area = studyAreaAdminService.load(me, studyAreaId);

        List<SeatMaster> targets = seatMasterRepository.findByStudyAreaId(area.getId()).stream()
                .filter(seat -> seatIds == null || seatIds.isEmpty()
                        || seatIds.contains(seat.getId()))
                .filter(seat -> !seat.isDeleted())
                .toList();
        if (targets.isEmpty()) {
            return 0;
        }

        // ★ 배정 조회를 한 번에 한다 — 좌석마다 부르면 92석 초기화에 쿼리가 92번 나간다
        List<String> occupied = seatAssignmentRepository
                .findActiveBySeatIds(targets.stream().map(SeatMaster::getId).toList()).stream()
                .map(a -> a.getSeat().getSeatCd())
                .sorted()
                .toList();
        if (!occupied.isEmpty()) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_OCCUPIED,
                    "배정된 좌석이 있어 삭제하지 않았습니다. 배정을 먼저 해제하세요: %s"
                            .formatted(String.join(", ", occupied)));
        }

        // soft delete 다 — 물리 삭제하면 seat_assignment 가 참조하는 행이 사라져
        // 과거 배정 이력이 끊긴다(단건 삭제와 같다)
        targets.forEach(SeatMaster::markDeleted);
        return targets.size();
    }

    private SeatMaster load(AuthPrincipal me, Long seatId) {
        SeatMaster seat = seatMasterRepository.findDetailById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
        if (!me.canAccessAcademy(seat.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return seat;
    }

    // ── 코드 충돌 ────────────────────────────────────────────────────────────

    /** 같은 구역의 같은 번호. 살아 있는 행이 먼저 오도록 정렬돼 있어 {@code putIfAbsent}다. */
    private Map<String, SeatMaster> existingByCd(StudyArea area, List<String> codes) {
        Map<String, SeatMaster> map = new LinkedHashMap<>();
        seatMasterRepository
                .findAnyByStudyAreaIdAndSeatCdIn(area.getId(), codes)
                .forEach(s -> map.putIfAbsent(s.getSeatCd(), s));
        return map;
    }

    /**
     * 살아 있는 좌석과 겹치면 거부한다.
     *
     * <p><b>겹친 코드를 전부 모아서 한 번에 알려준다.</b> 하나씩 알려주면 사용자가 격자를
     * 고쳐 올릴 때마다 충돌을 한 건씩만 발견하게 된다. 삭제분과 겹치는 것은 충돌이 아니라
     * <b>되살리기</b>다({@link #persist}).
     */
    private void rejectIfOccupied(StudyArea area, Map<String, SeatMaster> existing,
                                  List<String> codes) {
        List<String> conflicts = codes.stream()
                .map(existing::get)
                .filter(s -> s != null && !s.isDeleted())
                .map(SeatMaster::getSeatCd)
                .toList();
        if (conflicts.isEmpty()) {
            return;
        }
        throw new BusinessException(ErrorCode.SEAT_CD_DUPLICATED,
                "%s에 이미 있는 좌석번호입니다: %s".formatted(
                        area.getAreaNm(), String.join(", ", conflicts)));
    }

    /**
     * 키오스크에 내려갈 번호가 지점 안에서 겹치면 거부한다.
     *
     * <p><b>여기가 별관 때문에 새로 생긴 검사다.</b> 별관 1번은 1001번으로 내려가는데
     * 본관에 1001번이 실재할 수 있다. 그대로 두면 <b>단말에서만</b> 두 자리가 한 자리로
     * 보이고, 우리 DB·화면은 멀쩡해서 원인을 찾기가 매우 어렵다.
     *
     * <p>되살리기 대상(같은 구역의 삭제된 같은 번호)은 제외한다 — 그 행의 키오스크 번호가
     * 곧 지금 만들려는 값이라, 자기 자신과 겹친다고 거부하게 된다.
     */
    private void rejectIfKioskCodeTaken(StudyArea area, Map<String, SeatMaster> revivable,
                                        Map<String, String> kioskCdBySeatCd) {
        Set<Long> reviving = revivable.values().stream()
                .map(SeatMaster::getId)
                .collect(java.util.stream.Collectors.toSet());

        List<String> conflicts = seatMasterRepository
                .findByAcademyIdAndKioskSeatCdIn(area.getAcademy().getId(),
                        List.copyOf(kioskCdBySeatCd.values()))
                .stream()
                .filter(s -> !reviving.contains(s.getId()))
                .map(s -> "%s(%s %s번)".formatted(
                        s.getKioskSeatCd(), s.getStudyArea().getAreaNm(), s.getSeatCd()))
                .toList();
        if (conflicts.isEmpty()) {
            return;
        }
        throw new BusinessException(ErrorCode.SEAT_CD_DUPLICATED,
                "키오스크에 내려갈 좌석번호가 이미 쓰이고 있습니다: "
                        + String.join(", ", conflicts)
                        + " (관의 좌석번호 오프셋을 겹치지 않는 번호대로 잡으세요.)");
    }

    private SeatMaster persist(StudyArea area, Map<String, SeatMaster> existing, String seatCd,
                               String kioskSeatCd, String seatNm, int xPos, int yPos,
                               SeatStyle seatStyle) {
        SeatMaster deleted = existing.get(seatCd);
        if (deleted != null) {
            // 새로 만들면 seat_assignment 가 옛 행을 가리킨 채 남아 이력이 갈린다
            deleted.reviveAs(area, seatNm, xPos, yPos, seatStyle);
            return deleted;
        }
        return seatMasterRepository.save(
                new SeatMaster(area, seatCd, kioskSeatCd, seatNm, xPos, yPos, seatStyle));
    }

    /** 좌석번호 → 키오스크 번호. 순서를 지켜야 오류 메시지가 격자 순서대로 나온다. */
    private Map<String, String> kioskCodes(StudyArea area, List<String> seatCds) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String seatCd : seatCds) {
            map.put(seatCd, kioskCodeTranslator.seatCd(area.getBuilding(), seatCd));
        }
        return map;
    }

    // ── 격자 계산 ────────────────────────────────────────────────────────────

    private List<PlannedSeat> plan(SeatGridSpec spec) {
        if (spec.rows() < 1 || spec.columns() < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "행·열은 1 이상이어야 합니다.");
        }
        if (spec.rows() * spec.columns() > MAX_GRID_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 만들 수 있는 좌석은 " + MAX_GRID_SIZE + "개까지입니다.");
        }

        rejectIfRangesOverlap(spec.styleRanges());

        Set<String> skips = new HashSet<>();
        if (spec.skips() != null) {
            spec.skips().forEach(s -> skips.add(s.row() + ":" + s.column()));
        }

        int number = spec.startNumber();
        List<PlannedSeat> planned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 세로 우선이면 열을 먼저 훑는다. 번호는 빈 칸을 건너뛰고 이어진다
        //
        // ★ 역방향은 「좌표」가 아니라 「번호 붙는 순서」를 뒤집는다.
        //   좌표는 화면에 보이는 자리 그대로여야 하므로(1열이 왼쪽 끝) 그대로 두고,
        //   훑는 순서만 뒤집는다. 대구 A는 1번이 오른쪽 끝이라 reverseX,
        //   동탄 별관 C는 1201이 오른쪽 아래라 둘 다 뒤집는다.
        List<Integer> rowOrder = sequence(spec.rows(), spec.reverseY());
        List<Integer> columnOrder = sequence(spec.columns(), spec.reverseX());
        List<Integer> outer = spec.columnMajor() ? columnOrder : rowOrder;
        List<Integer> inner = spec.columnMajor() ? rowOrder : columnOrder;

        for (int o : outer) {
            for (int i : inner) {
                int row = spec.columnMajor() ? i : o;
                int column = spec.columnMajor() ? o : i;
                if (skips.contains(row + ":" + column)) {
                    continue;
                }
                int seatNumber = number++;
                String seatCd = spec.seatCdPrefix() + pad(seatNumber, spec.numberPadding());
                if (!seen.add(seatCd)) {
                    // 접두어·시작번호 조합이 스스로 겹치면 DB 제약보다 먼저 잡는다
                    throw new BusinessException(ErrorCode.SEAT_CD_DUPLICATED,
                            "생성될 좌석번호가 서로 겹칩니다: " + seatCd);
                }
                planned.add(new PlannedSeat(seatCd,
                        spec.startX() + column - 1,
                        spec.startY() + row - 1,
                        styleOf(seatNumber, spec.styleRanges(), spec.seatStyle())));
            }
        }
        if (planned.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "만들 좌석이 없습니다.");
        }
        return planned;
    }

    /** {@code 1..n} 또는 뒤집힌 {@code n..1}. */
    private List<Integer> sequence(int size, boolean reverse) {
        List<Integer> seq = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            seq.add(reverse ? size - i + 1 : i);
        }
        return seq;
    }

    // ── 좌석형태 ────────────────────────────────────────────────────────────

    /**
     * 범위가 서로 겹치면 거부한다.
     *
     * <p>겹치면 <b>먼저 쓴 쪽이 이기는</b> 숨은 규칙이 생기고, 시트를 옮겨 적다 한 줄을
     * 잘못 적은 것이 조용히 통과한다. 뒤집힌 범위(시작 &gt; 끝)도 여기서 잡는다.
     */
    private void rejectIfRangesOverlap(List<StyleRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return;
        }
        List<StyleRange> sorted = ranges.stream()
                .sorted(java.util.Comparator.comparingInt(StyleRange::fromNumber))
                .toList();
        for (StyleRange r : sorted) {
            if (r.fromNumber() > r.toNumber()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "좌석형태 범위의 시작이 끝보다 큽니다: %d~%d"
                                .formatted(r.fromNumber(), r.toNumber()));
            }
        }
        for (int i = 1; i < sorted.size(); i++) {
            StyleRange prev = sorted.get(i - 1);
            StyleRange cur = sorted.get(i);
            if (cur.fromNumber() <= prev.toNumber()) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "좌석형태 범위가 겹칩니다: %d~%d 와 %d~%d".formatted(
                                prev.fromNumber(), prev.toNumber(),
                                cur.fromNumber(), cur.toNumber()));
            }
        }
    }

    /** 번호가 든 범위의 형태. 어느 범위에도 안 들면 기본값이다. */
    private SeatStyle styleOf(int number, List<StyleRange> ranges, SeatStyle fallback) {
        if (ranges != null) {
            for (StyleRange r : ranges) {
                if (number >= r.fromNumber() && number <= r.toNumber()) {
                    return r.seatStyle();
                }
            }
        }
        return fallback;
    }

    /**
     * 좌석번호 끝에 붙은 숫자({@code A-01} → 1).
     *
     * <p>시트가 번호 범위로 오는데 좌석번호에 접두어가 붙어 있을 수 있어서다.
     * 숫자가 없으면 {@code null} — 범위에 들 수 없다.
     */
    private Integer trailingNumber(String seatCd) {
        int end = seatCd.length();
        int start = end;
        while (start > 0 && Character.isDigit(seatCd.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return null;
        }
        try {
            return Integer.valueOf(seatCd.substring(start));
        } catch (NumberFormatException e) {
            // 자릿수가 int 를 넘는 경우. 번호로 다룰 값이 아니다
            return null;
        }
    }

    private String pad(int number, int width) {
        String s = String.valueOf(number);
        return s.length() >= width ? s : "0".repeat(width - s.length()) + s;
    }

    private record PlannedSeat(String seatCd, int xPos, int yPos, SeatStyle seatStyle) {
    }

    /**
     * 격자 등록 입력.
     *
     * @param seatCdPrefix  좌석번호 접두어. {@code "A-"} + 번호로 {@code A-01}이 된다
     * @param startNumber   시작 번호. 이어붙이기(41번부터)를 위해 지정할 수 있다
     * @param numberPadding 번호 자릿수. {@code 2}면 {@code 01}
     * @param startX        좌표 시작값. 기존 격자 옆에 붙일 때 쓴다
     * @param columnMajor   세로 우선 번호매김
     * @param reverseX      번호를 오른쪽에서 왼쪽으로 붙인다(대구 A)
     * @param reverseY      번호를 아래에서 위로 붙인다(동탄 별관 C는 둘 다)
     * @param skips         통로 등 좌석이 없는 칸(행·열은 1부터)
     * @param seatStyle     범위 밖 좌석의 형태
     * @param styleRanges   번호 범위별 형태. 한 구역에 형태가 섞여 있다
     */
    public record SeatGridSpec(
            Long studyAreaId,
            int rows,
            int columns,
            String seatCdPrefix,
            int startNumber,
            int numberPadding,
            int startX,
            int startY,
            boolean columnMajor,
            boolean reverseX,
            boolean reverseY,
            List<GridSkip> skips,
            SeatStyle seatStyle,
            List<StyleRange> styleRanges) {
    }

    /** 격자에서 비워 둘 칸. */
    public record GridSkip(int row, int column) {
    }

    /** 번호 범위별 좌석형태. 양 끝을 포함한다. */
    public record StyleRange(int fromNumber, int toNumber, SeatStyle seatStyle) {
    }
}
