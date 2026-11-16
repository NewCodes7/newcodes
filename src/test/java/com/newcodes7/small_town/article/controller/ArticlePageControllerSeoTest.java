package com.newcodes7.small_town.article.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleIndexPolicy;
import com.newcodes7.small_town.embedding.service.RelatedArticleService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.exception.GlobalExceptionHandler;
import com.newcodes7.small_town.global.exception.RestApiExceptionHandler;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.View;

/**
 * 아티클 상세 페이지의 SEO 관련 응답(404, 301, 정규 URL, ISO 8601 날짜)을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ArticlePageControllerSeoTest {

    private static final String BASE_URL = "https://newcodes.net";
    private static final String TITLE = "Java의 Virtual Thread 2편: 동작 원리";
    private static final String CANONICAL_SLUG = "java의-virtual-thread-2편-동작-원리";

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private RelatedArticleService relatedArticleService;

    @Spy
    private ArticleIndexPolicy articleIndexPolicy = new ArticleIndexPolicy(true);

    @InjectMocks
    private ArticlePageController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "baseUrl", BASE_URL);
    }

    @Test
    @DisplayName("없는 글 ID는 RuntimeException(500)이 아니라 404를 던진다")
    void articleDetail_없는글_404() {
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.articleDetail("999-anything", new ConcurrentModel(),
                new MockHttpServletRequest(), new MockHttpServletResponse()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("삭제된 글은 404")
    void articleDetail_삭제된글_404() {
        Article deleted = article(1L, LocalDateTime.of(2026, 7, 22, 10, 9, 20), LocalDateTime.now());
        when(articleRepository.findById(1L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> controller.articleDetail("1-" + CANONICAL_SLUG, new ConcurrentModel(),
                new MockHttpServletRequest(), new MockHttpServletResponse()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("슬러그가 정규 슬러그와 다르면 정규 URL로 301")
    void articleDetail_슬러그불일치_301() {
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article(1L, null, null)));
        MockHttpServletRequest request = new MockHttpServletRequest();

        String view = controller.articleDetail("1-wrong-slug", new ConcurrentModel(), request,
            new MockHttpServletResponse());

        assertThat(view).isEqualTo("redirect:" + canonicalPath(1L));
        assertThat(request.getAttribute(View.RESPONSE_STATUS_ATTRIBUTE)).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
    }

    @Test
    @DisplayName("정규 슬러그(디코딩된 한글)로 들어오면 리다이렉트 없이 렌더링하고, JSON-LD용 날짜는 ISO 8601")
    void articleDetail_정규슬러그_렌더링() {
        when(articleRepository.findById(1L))
            .thenReturn(Optional.of(article(1L, LocalDateTime.of(2026, 7, 22, 10, 9, 20), null)));
        Model model = new ConcurrentModel();
        MockHttpServletRequest request = new MockHttpServletRequest();

        String view = controller.articleDetail("1-" + CANONICAL_SLUG, model, request, new MockHttpServletResponse());

        assertThat(view).isEqualTo("article-detail");
        assertThat(request.getAttribute(View.RESPONSE_STATUS_ATTRIBUTE)).isNull();
        assertThat(model.getAttribute("canonicalUrl")).isEqualTo(BASE_URL + canonicalPath(1L));
        // publishedAt은 KST 벽시계 시각(hibernate.jdbc.time_zone=Asia/Seoul)이라 +09:00을 붙인다
        assertThat(model.getAttribute("publishedAtIso")).isEqualTo("2026-07-22T10:09:20+09:00");
    }

    @Test
    @DisplayName("Last-Modified는 KST 시각을 UTC로 변환한 값(9시간 이른 GMT)")
    void articleDetail_LastModified_KST기준() {
        when(articleRepository.findById(1L))
            .thenReturn(Optional.of(article(1L, LocalDateTime.of(2026, 7, 22, 10, 9, 20), null)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.articleDetail("1-" + CANONICAL_SLUG, new ConcurrentModel(), new MockHttpServletRequest(), response);

        assertThat(response.getHeader("Last-Modified")).isEqualTo("Wed, 22 Jul 2026 01:09:20 GMT");
    }

    @Test
    @DisplayName("요약 없는 글은 noindex(articleIndexable=false), 설명문은 제목·회사 기본 문구")
    void articleDetail_요약없음_noindex() {
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article(1L, null, null)));
        Model model = new ConcurrentModel();

        controller.articleDetail("1-" + CANONICAL_SLUG, model, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(model.getAttribute("articleIndexable")).isEqualTo(false);
        assertThat((String) model.getAttribute("metaDescription")).startsWith(TITLE + " - NewCodes 기술 블로그");
    }

    @Test
    @DisplayName("요약 있는 글은 index, 설명문은 요약을 한 문단으로(글머리·줄바꿈 제거)")
    void articleDetail_요약있음_index() {
        Article article = article(1L, null, null);
        article.setSummary("• 가상 스레드는 JDK 21에서 정식 도입됐다\n• 캐리어 스레드에 마운트되어 실행된다\n• 블로킹 시 언마운트된다");
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        Model model = new ConcurrentModel();

        controller.articleDetail("1-" + CANONICAL_SLUG, model, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(model.getAttribute("articleIndexable")).isEqualTo(true);
        assertThat(model.getAttribute("metaDescription"))
            .isEqualTo("가상 스레드는 JDK 21에서 정식 도입됐다 캐리어 스레드에 마운트되어 실행된다 블로킹 시 언마운트된다");
    }

    @Test
    @DisplayName("원문 RSS 발췌가 summary에 남은 글은 AI 요약이 아니므로 noindex, AI 요약 영역·설명문에 쓰지 않는다")
    void articleDetail_원문발췌_noindex() {
        Article article = article(1L, null, null);
        article.setSummary("Date:2025.08.07");
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        Model model = new ConcurrentModel();

        controller.articleDetail("1-" + CANONICAL_SLUG, model, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(model.getAttribute("articleIndexable")).isEqualTo(false);
        assertThat(model.getAttribute("aiSummary")).isNull();
        assertThat((String) model.getAttribute("metaDescription")).startsWith(TITLE + " - NewCodes 기술 블로그");
    }

    @Test
    @DisplayName("정책을 끄면(index-requires-summary=false) 요약이 없어도 index")
    void articleDetail_정책off_index() {
        ReflectionTestUtils.setField(controller, "articleIndexPolicy", new ArticleIndexPolicy(false));
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article(1L, null, null)));
        Model model = new ConcurrentModel();

        controller.articleDetail("1-" + CANONICAL_SLUG, model, new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(model.getAttribute("articleIndexable")).isEqualTo(true);
    }

    @Test
    @DisplayName("/articles/{id}는 정규 URL로 301(302 아님)")
    void articleDetailRedirect_ID만_301() {
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article(1L, null, null)));
        MockHttpServletRequest request = new MockHttpServletRequest();

        String view = controller.articleDetailRedirect(1L, request);

        assertThat(view).isEqualTo("redirect:" + canonicalPath(1L));
        assertThat(request.getAttribute(View.RESPONSE_STATUS_ATTRIBUTE)).isEqualTo(HttpStatus.MOVED_PERMANENTLY);
    }

    @Test
    @DisplayName("예외 처리 체인을 거쳐도 뷰 요청의 404가 500으로 바뀌지 않는다")
    void 없는글_예외처리체인_404() throws Exception {
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new RestApiExceptionHandler(), new GlobalExceptionHandler())
            .build();

        mockMvc.perform(get("/articles/999-anything"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("예외 처리 체인을 거쳐 /articles/{id}가 301과 상대 경로 Location으로 나간다")
    void ID만_예외처리체인_301() throws Exception {
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article(1L, null, null)));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/articles/1"))
            .andExpect(status().isMovedPermanently())
            .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo(canonicalPath(1L)));
    }

    private static String canonicalPath(long id) {
        return "/articles/" + id + "-" + URLEncoder.encode(CANONICAL_SLUG, StandardCharsets.UTF_8);
    }

    private static Article article(long id, LocalDateTime publishedAt, LocalDateTime deletedAt) {
        Corporation corporation = Corporation.builder()
            .id(10L)
            .name("NewCodes")
            .homeLink("https://newcodes.net")
            .blogLink("https://newcodes.net/blog")
            .isDomestic(1)
            .build();
        return Article.builder()
            .id(id)
            .title(TITLE)
            .link("https://example.com/original-post")
            .corporation(corporation)
            .publishedAt(publishedAt)
            .deletedAt(deletedAt)
            .build();
    }
}
