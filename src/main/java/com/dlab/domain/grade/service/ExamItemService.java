package com.dlab.domain.grade.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.grade.entity.ExamItem;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.repository.ExamItemRepository;
import com.dlab.domain.grade.repository.ExamMasterRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 문항 정보 반영 — 문항분석표 + 정답률.
 *
 * <h2>통째로 교체한다</h2>
 * 문항 정보는 회차당 한 벌이다. 다시 올리면 이전 것을 내리고 새로 넣는다 — 일부만 고치는
 * 경로를 두면 두 파일 중 하나만 바뀐 상태가 남는다.
 *
 * <h2>★ 이어지지 않은 정답률은 버리지 않고 알린다</h2>
 * 과목명 표기가 파일마다 달라 정규화로 잇는데, 통합수능 전환으로 새 과목명이 들어오면
 * 안 이어질 수 있다. 조용히 버리면 그 과목 정답률이 앱에서 비는데 아무도 모른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExamItemService {

    private final ExamItemParser parser;
    private final ExamItemRepository itemRepository;
    private final ExamMasterRepository examMasterRepository;

    /**
     * @param rates 정답률 파일. <b>없어도 된다</b> — 문항분석표만 먼저 올리고 정답률은 나중에
     *              같이 다시 올릴 수 있다
     */
    @Transactional
    public Result upload(AuthPrincipal me, Long examMasterId, InputStream analysis,
                         InputStream rates) {
        ExamMaster exam = examMasterRepository.findById(examMasterId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.EXAM_MASTER_NOT_FOUND));
        if (!exam.isAcademyExam()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "입학 전 성적 양식에는 문항 정보를 올릴 수 없습니다. 디랩에서 본 시험 회차를 골라 주세요.");
        }
        if (!exam.isCommon()) {
            me.requireAcademyScope(exam.getAcademy().getId());
        }

        List<ExamItemParser.AnalysisRow> rows = parser.parseAnalysis(analysis);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "문항분석표에서 문항을 찾지 못했습니다. 첫 시트(입력부)와 헤더를 확인해 주세요.");
        }

        itemRepository.softDeleteByExam(examMasterId);

        Map<String, ExamItem> byKey = new HashMap<>();
        for (ExamItemParser.AnalysisRow r : rows) {
            ExamItem item = new ExamItem(exam, r.subjectCode(), r.subjectName(), r.questionNo(),
                    r.answer(), r.points(), r.elective(),
                    r.unitCode(), r.unitName(), r.skillCode(), r.skillName());
            byKey.put(item.getSubjectKey() + "#" + item.getQuestionNo(), item);
        }

        int applied = 0;
        List<String> unmatched = new ArrayList<>();
        if (rates != null) {
            for (ExamItemParser.RateRow r : parser.parseRates(rates)) {
                ExamItem item = byKey.get(SubjectNames.key(r.subjectName()) + "#" + r.questionNo());
                if (item == null) {
                    unmatched.add("%s %d번".formatted(r.subjectName(), r.questionNo()));
                    continue;
                }
                item.applyRates(r.nationalRate(), r.choiceRates(), r.discrimination());
                applied++;
            }
        }

        itemRepository.saveAll(byKey.values());
        log.info("문항 정보 반영: examMasterId={}, 문항={}, 정답률={}, 미연결={}",
                examMasterId, byKey.size(), applied, unmatched.size());
        return new Result(byKey.size(), applied, unmatched);
    }

    /**
     * @param unmatchedRates 문항분석표에 없는 정답률 행. 과목명 표기가 달라졌을 가능성이 크다 —
     *                       비어 있지 않으면 화면이 경고해야 한다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "ExamItemUploadResult")
    public record Result(int itemCount, int ratesApplied, List<String> unmatchedRates) {
    }
}
