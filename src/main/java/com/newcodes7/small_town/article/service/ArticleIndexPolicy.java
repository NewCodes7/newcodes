package com.newcodes7.small_town.article.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 아티클 상세 페이지를 검색엔진 색인 대상으로 둘지 정하는 단일 규칙.
 *
 * <p>상세 페이지는 제목·회사·날짜·원문 링크만 있으면 원문과 경쟁하는 얇은 페이지가 된다
 * (2만 개 규모라 사이트 전체 품질 평가를 끌어내린다). 고유 텍스트인 AI 요약(Article.summary)이
 * 있는 글만 색인하고, 나머지는 {@code noindex, follow}로 링크만 따라가게 한다.
 *
 * <p>상세 페이지의 robots 메타와 사이트맵이 같은 규칙을 써야 "사이트맵엔 있는데 noindex"인
 * 모순 신호가 생기지 않으므로, 판단은 이 클래스 한 곳에서만 한다.
 * {@code seo.article.index-requires-summary=false}로 끄면 이전 동작(전부 색인)으로 돌아간다.
 */
@Component
public class ArticleIndexPolicy {

    private final boolean indexRequiresSummary;

    public ArticleIndexPolicy(@Value("${seo.article.index-requires-summary:true}") boolean indexRequiresSummary) {
        this.indexRequiresSummary = indexRequiresSummary;
    }

    /**
     * AI 3줄 요약 표식. {@code ArticleSummaryResponse.toDisplayText()}는 줄마다 "• "로 시작하는 형식만 만든다.
     * Article.summary 컬럼에는 예전 크롤러가 넣은 원문 RSS 발췌("Date:2025.08.07", 목차 텍스트 등)가
     * 3천여 건 남아 있어서, "값이 있다"가 아니라 이 형식인지로 AI 요약을 가려야 한다.
     * 사이트맵·요약 생성 배치 쿼리도 같은 표식('•%')을 쓴다.
     */
    public static final String AI_SUMMARY_MARKER = "\u2022";

    public static boolean isAiSummary(String summary) {
        return StringUtils.hasText(summary) && summary.strip().startsWith(AI_SUMMARY_MARKER);
    }

    public boolean isIndexable(String summary) {
        return !indexRequiresSummary || isAiSummary(summary);
    }

    /** 사이트맵이 AI 요약 있는 글만 실어야 하는지 */
    public boolean isIndexRequiresSummary() {
        return indexRequiresSummary;
    }
}
