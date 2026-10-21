package com.dlab.api.app.vocab;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 앱 — D:VOCA 요청. */
public final class AppVocabRequest {

    private AppVocabRequest() {
    }

    /**
     * 제출.
     *
     * <p>★ <b>객관식도 「고른 문구」로 보낸다.</b> 보기 순서를 섞어 내리므로 인덱스는
     * 앱과 서버가 어긋날 수 있고, 그러면 <b>맞게 고른 학생이 틀린다.</b>
     */
    @Schema(name = "VocabSubmitRequest", description = "단어 시험 제출")
    public record Submit(
            @Schema(description = "문항별 답. ★ 안 보낸 문항은 무응답으로 채점된다")
            @NotNull List<Answer> answers) {

        public Map<Short, String> asMap() {
            return answers.stream().collect(Collectors.toMap(
                    Answer::seqNo, a -> a.given() == null ? "" : a.given(),
                    // 같은 번호가 두 번 오면 ★ 뒤의 것을 쓴다 — 앱이 고쳐 보낸 것으로 본다
                    (first, second) -> second));
        }
    }

    @Schema(name = "VocabSubmitAnswer", description = "문항별 답")
    public record Answer(
            @Schema(description = "문항 번호(1-based)") @NotNull Short seqNo,
            @Schema(description = "학생 답. 객관식은 고른 보기 문구") String given) {
    }
}
