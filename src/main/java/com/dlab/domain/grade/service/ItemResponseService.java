package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamItem;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.StudentItemResponse;
import com.dlab.domain.grade.repository.ExamItemRepository;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import com.dlab.domain.grade.repository.StudentItemResponseRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 정오·답안 반영 — 정오표(필수) + 답안표(선택).
 *
 * <h2>★ 문항 정보를 먼저 올려야 한다</h2>
 * 국어·수학은 한 영역이 공통(1~34번)과 선택(35~45번)으로 갈리는데, <b>어디서 갈리는지는
 * 문항분석표가 안다.</b> 경계를 코드에 박으면 통합수능 전환(선택과목 폐지) 때 틀린다.
 *
 * <h2>학생 매칭은 성적 업로드와 같은 규칙이다</h2>
 * {@link MockExamStudentMatcher} — 외부생 제외 → 연결 키 → 이름. 규칙이 다르면 한 학생이
 * 성적에서는 잡히고 정오표에서는 빠져, 앱에 점수는 있는데 채점이 비어 뜬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemResponseService {

    private final ItemSheetParser sheetParser;
    private final MockExamStudentMatcher matcher;
    private final ExamMasterRepository examMasterRepository;
    private final ExamItemRepository itemRepository;
    private final StudentItemResponseRepository responseRepository;

    @Transactional
    public Result upload(AuthPrincipal me, Long requestedAcademyId, Long examMasterId,
                         InputStream results, InputStream answers) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);
        ExamMaster exam = examMasterRepository.findById(examMasterId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXAM_MASTER_NOT_FOUND));
        if (!exam.isAcademyExam()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "입학 전 성적 양식에는 올릴 수 없습니다. 디랩에서 본 시험 회차를 골라 주세요.");
        }
        if (!exam.isCommon() && !exam.getAcademy().getId().equals(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        ItemIndex index = ItemIndex.of(itemRepository.findByExamMasterId(examMasterId));
        if (index.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이 회차의 문항 정보가 없습니다. 문항분석표를 먼저 올려 주세요 — "
                            + "공통·선택 문항의 경계를 거기서 알아야 합니다.");
        }

        Map<String, ItemSheetParser.StudentSheet> answerSheets = new HashMap<>();
        if (answers != null) {
            for (ItemSheetParser.StudentSheet s : sheetParser.parse(answers)) {
                answerSheets.put(MockExamStudentMatcher.fileKey(s.schoolCode(), s.classNo(),
                        s.studentNo()), s);
            }
        }

        MockExamStudentMatcher.Table table = matcher.prepare(academyId, exam.getYear());
        int saved = 0;
        int skippedExternal = 0;
        List<Unmatched> unmatched = new ArrayList<>();
        Set<String> unknownSubjects = new TreeSet<>();

        for (ItemSheetParser.StudentSheet sheet : sheetParser.parse(results)) {
            MockExamStudentMatcher.Match match = table.match(sheet.schoolCode(), sheet.classNo(),
                    sheet.studentNo(), sheet.name());
            if (match.external()) {
                skippedExternal++;
                continue;
            }
            if (match.enrollment() == null) {
                unmatched.add(new Unmatched(sheet.rowNumber(), sheet.name(), match.reason()));
                continue;
            }
            ItemSheetParser.StudentSheet answerSheet = answerSheets.get(MockExamStudentMatcher
                    .fileKey(sheet.schoolCode(), sheet.classNo(), sheet.studentNo()));
            saveStudent(match.enrollment(), exam, index, sheet, answerSheet, unknownSubjects);
            saved++;
        }

        log.info("정오·답안 반영: examMasterId={}, 반영={}명, 미매칭={}, 외부생={}, 모르는 과목={}",
                examMasterId, saved, unmatched.size(), skippedExternal, unknownSubjects);
        return new Result(saved, skippedExternal, unmatched, List.copyOf(unknownSubjects));
    }

    private void saveStudent(StudentEnrollment enrollment, ExamMaster exam, ItemIndex index,
                             ItemSheetParser.StudentSheet sheet,
                             ItemSheetParser.StudentSheet answerSheet, Set<String> unknown) {
        // 과목 키 → (문항 → [정오, 답])
        Map<String, SortedMap<Integer, String[]>> bySubject = new LinkedHashMap<>();
        for (ItemSheetParser.Block block : sheet.blocks()) {
            Optional<String> chosen = SubjectNames.fromAbbreviation(block.abbreviation());
            if (chosen.isEmpty()) {
                // ★ 버리지 않고 알린다 — 통합수능 전환으로 새 과목명이 들어올 예정이다
                unknown.add(block.abbreviation());
                continue;
            }
            if (!index.knows(chosen.get())) {
                // ★ 대응표에는 있는데 이 회차 문항분석표에 그 과목이 없다. 공통 문항까지
                //   어디에 붙일지 몰라 통째로 빠지므로 알린다
                unknown.add(block.abbreviation() + "(문항 정보 없음)");
                continue;
            }
            Map<Integer, String> answerValues = answerSheet == null ? Map.of()
                    : answerSheet.blocks().stream()
                            .filter(b -> b.index() == block.index())
                            .findFirst().map(ItemSheetParser.Block::values).orElse(Map.of());

            block.values().forEach((no, value) -> index.subjectOf(chosen.get(), no)
                    .ifPresent(subjectKey -> bySubject
                            .computeIfAbsent(subjectKey, k -> new TreeMap<>())
                            .put(no, new String[]{value, answerValues.getOrDefault(no, "")})));
        }

        responseRepository.softDeleteOf(enrollment.getId(), exam.getId());
        responseRepository.flush();
        bySubject.forEach((subjectKey, questions) -> {
            int first = questions.firstKey();
            int last = questions.lastKey();
            StringBuilder marks = new StringBuilder();
            List<String> chosenAnswers = new ArrayList<>();
            for (int no = first; no <= last; no++) {
                String[] v = questions.get(no);
                marks.append(mark(v == null ? "" : v[0]));
                chosenAnswers.add(v == null ? "" : v[1]);
            }
            responseRepository.save(new StudentItemResponse(enrollment, exam, subjectKey,
                    (short) first, marks.toString(), chosenAnswers));
        });
    }

    /** 정오 한 칸 — O·X 가 아니면 비었다고 본다 */
    private static char mark(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (v.equals("O")) {
            return StudentItemResponse.CORRECT;
        }
        if (v.equals("X")) {
            return StudentItemResponse.WRONG;
        }
        return StudentItemResponse.BLANK;
    }

    /**
     * 문항 → 과목 키. 선택과목에 그 번호가 있으면 선택과목, 없으면 <b>같은 과목 코드의 공통</b>.
     * 국어 1~34번은 공통(국어), 35~45번은 선택(언어와매체)이 된다.
     */
    private record ItemIndex(Map<String, Set<Integer>> questionsByKey,
                             Map<String, String> commonKeyOf) {

        static ItemIndex of(List<ExamItem> items) {
            Map<String, Set<Integer>> byKey = new HashMap<>();
            Map<String, String> codeOf = new HashMap<>();
            Map<String, String> commonByCode = new HashMap<>();
            for (ExamItem i : items) {
                byKey.computeIfAbsent(i.getSubjectKey(), k -> new HashSet<>()).add((int) i.getQuestionNo());
                if (i.getSubjectCode() != null) {
                    codeOf.put(i.getSubjectKey(), i.getSubjectCode());
                    if (!i.isElective()) {
                        commonByCode.putIfAbsent(i.getSubjectCode(), i.getSubjectKey());
                    }
                }
            }
            Map<String, String> commonKeyOf = new HashMap<>();
            codeOf.forEach((key, code) -> {
                String common = commonByCode.get(code);
                if (common != null && !common.equals(key)) {
                    commonKeyOf.put(key, common);
                }
            });
            return new ItemIndex(byKey, commonKeyOf);
        }

        boolean isEmpty() {
            return questionsByKey.isEmpty();
        }

        /** 이 회차 문항분석표에 그 과목이 있는가 */
        boolean knows(String subjectKey) {
            return questionsByKey.containsKey(subjectKey);
        }

        Optional<String> subjectOf(String chosenKey, int questionNo) {
            if (questionsByKey.getOrDefault(chosenKey, Set.of()).contains(questionNo)) {
                return Optional.of(chosenKey);
            }
            String common = commonKeyOf.get(chosenKey);
            if (common != null && questionsByKey.getOrDefault(common, Set.of()).contains(questionNo)) {
                return Optional.of(common);
            }
            return Optional.empty();
        }
    }

    public record Unmatched(int rowNumber, String name, String reason) {
    }

    /**
     * @param unknownSubjects 대응표에 없는 과목 약어. 비어 있지 않으면 <b>그 과목 채점이 빠졌다</b>
     */
    public record Result(int savedStudents, int skippedExternal, List<Unmatched> unmatched,
                         List<String> unknownSubjects) {
    }
}
