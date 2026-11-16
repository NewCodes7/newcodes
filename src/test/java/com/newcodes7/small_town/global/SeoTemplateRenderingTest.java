package com.newcodes7.small_town.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.article.controller.ArticlePageController;
import com.newcodes7.small_town.article.controller.CorporationPageController;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.RelatedArticleDto;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleIndexPolicy;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.corporation.dto.CorporationDetailDto;
import com.newcodes7.small_town.embedding.service.RelatedArticleService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.theme.dto.ThemeResponseDto;
import com.newcodes7.small_town.theme.entity.Theme;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.expression.IExpressionObjectFactory;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;
import tools.jackson.databind.ObjectMapper;

/**
 * SEO 태그를 바꾼 템플릿이 실제로 렌더링되는지(표현식 오류 없이) 확인하는 스모크 테스트.
 * DB 없이 돌도록 컨트롤러를 목으로 실행해 얻은 모델을 그대로 템플릿에 넣는다.
 * sec:authorize / #authorization은 비로그인 사용자 기준 스텁으로 대신한다(관리자 영역은 렌더링하지 않음).
 */
class SeoTemplateRenderingTest {

    private static final String BASE_URL = "https://newcodes.net";
    private static final Pattern JSON_LD = Pattern.compile(
        "<script type=\"application/ld\\+json\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    private SpringTemplateEngine engine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(new LayoutDialect());
        engine.addDialect(new AnonymousSecurityDialect());
    }

    @Test
    @DisplayName("아티클 상세: 요약 있는 글은 index + 서버 렌더링 연관 글 링크 + 유효한 JSON-LD")
    void articleDetail_요약있음() {
        Model model = renderArticleModel("• 첫째 요약\n• 둘째 요약\n• 셋째 요약");

        String html = render("article-detail", model);

        assertThat(html).contains("<meta name=\"robots\" content=\"index, follow\">");
        assertThat(html).containsOnlyOnce("name=\"description\"");
        assertThat(html).contains("content=\"첫째 요약 둘째 요약 셋째 요약\"");
        assertThat(html).contains("class=\"article-ai-summary\"");
        assertThat(html).contains("data-ssr=\"true\"");
        assertThat(html).contains("href=\"/articles/2-");                 // 서버 렌더링 연관 글 내부 링크
        assertThat(html).doesNotContain("related-articles-loading");
        assertThat(html).contains("href=\"/about\"");                     // 푸터 서비스 소개 링크
        assertJsonLdParses(html);
        assertThat(html).containsPattern("\"datePublished\":\\s*\"2026-07-22T10:09:20\\+09:00\"");
    }

    @Test
    @DisplayName("아티클 상세: 요약 없는 글은 noindex, 연관 글은 기존 JS 로딩")
    void articleDetail_요약없음() {
        Model model = renderArticleModel(null);

        String html = render("article-detail", model);

        assertThat(html).contains("<meta name=\"robots\" content=\"noindex, follow\">");
        assertThat(html).containsOnlyOnce("name=\"description\"");
        assertThat(html).doesNotContain("class=\"article-ai-summary\"");
        assertThat(html).contains("data-ssr=\"false\"");
        assertThat(html).contains("related-articles-loading");
        assertJsonLdParses(html);
    }

    @Test
    @DisplayName("아티클 상세: summary가 원문 RSS 발췌면 noindex, \"AI 3줄 요약\" 영역을 그리지 않는다")
    void articleDetail_원문발췌() {
        Model model = renderArticleModel("DDL 관리는 어떻게 하고 계신가요?");

        String html = render("article-detail", model);

        assertThat(html).contains("<meta name=\"robots\" content=\"noindex, follow\">");
        assertThat(html).doesNotContain("class=\"article-ai-summary\"");
        assertThat(html).doesNotContain("DDL 관리는 어떻게 하고 계신가요?");
        assertThat(html).contains("data-ssr=\"false\"");
    }

    @Test
    @DisplayName("기업 상세: 소개문이 본문과 설명문에 들어가고 페이지네이션 이전 링크가 없다")
    void corporationDetail() {
        ArticleService articleService = mock(ArticleService.class);
        CorporationPageController controller = new CorporationPageController(articleService, null, null, null, null, null);
        ReflectionTestUtils.setField(controller, "baseUrl", BASE_URL);
        Corporation corporation = corporation();
        when(articleService.getCorporationDetail(10L)).thenReturn(new CorporationDetailDto(corporation, 1234));
        ArticleListResponseDto latest = new ArticleListResponseDto(article(1L, "최근 글", null));
        when(articleService.getArticlesByCorporation(eq(10L), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(latest), org.springframework.data.domain.PageRequest.of(0, 10), 30));
        Model model = new ConcurrentModel();

        controller.corporationDetail(10L, 0, 10, null, null, new MockHttpServletRequest(), model);
        String html = render("corporation-detail", model);

        assertThat(html).contains("<title>NewCodes 기술 블로그 모음 - NewCodes</title>");
        assertThat(html).contains("NewCodes 기술 블로그에 올라온 글 1,234개를 최신순으로 모았습니다. 가장 최근 글은 2026-07-22에 올라왔습니다.");
        assertThat(html).containsOnlyOnce("name=\"description\"");
        assertThat(html).doesNotContain("?page=-1");
        assertJsonLdParses(html);
    }

    @Test
    @DisplayName("테마 목록·상세: 페이지별 description과 og 태그")
    void themes() {
        Theme theme = Theme.builder().id(5L).name("신입 개발자라면 읽어봐요!").description("신입에게 권하는 글 모음").build();
        ThemeResponseDto dto = new ThemeResponseDto(theme, List.of(), 0, 0, null);

        String list = render("themes", modelOf(Map.of("themes", List.of(dto), "canonicalUrl", BASE_URL + "/themes")));
        String detail = render("theme-detail", modelOf(Map.of("theme", dto, "canonicalUrl", BASE_URL + "/themes/5")));

        // 정적 <title>이 레이아웃 기본 제목에 덮이지 않아야 한다(/, /articles, /themes, /about이 같은 제목이던 문제)
        assertThat(list).contains("<title>테마 - NewCodes</title>");
        assertThat(list).containsOnlyOnce("name=\"description\"");
        assertThat(list).contains("<link rel=\"canonical\" href=\"https://newcodes.net/themes\">");
        assertThat(detail).contains("<meta name=\"description\" content=\"신입에게 권하는 글 모음\">");
        assertThat(detail).contains("<link rel=\"canonical\" href=\"https://newcodes.net/themes/5\">");
    }

    private Model renderArticleModel(String summary) {
        ArticleRepository articleRepository = mock(ArticleRepository.class);
        RelatedArticleService relatedArticleService = mock(RelatedArticleService.class);
        ArticlePageController controller = new ArticlePageController(
            null, new ArticleIndexPolicy(true), null, null, null, null, null, relatedArticleService, null,
            articleRepository, null, null);
        ReflectionTestUtils.setField(controller, "baseUrl", BASE_URL);

        Article article = article(1L, "Java의 Virtual Thread 2편: 동작 원리", summary);
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        RelatedArticleDto related = new RelatedArticleDto(article(2L, "가상 스레드 실측", null), 0.9, null);
        when(relatedArticleService.getRelatedArticles(anyLong(), anyInt())).thenReturn(List.of(related));

        Model model = new ConcurrentModel();
        controller.articleDetail("1-java의-virtual-thread-2편-동작-원리", model, new MockHttpServletRequest(),
            new MockHttpServletResponse());
        return model;
    }

    private String render(String template, Model model) {
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(new MockServletContext());
        var exchange = application.buildExchange(new MockHttpServletRequest(), new MockHttpServletResponse());
        return engine.process(template, new WebContext(exchange, Locale.KOREAN, model.asMap()));
    }

    private void assertJsonLdParses(String html) {
        Matcher matcher = JSON_LD.matcher(html);
        int blocks = 0;
        while (matcher.find()) {
            String json = matcher.group(1);
            assertThat(objectMapper.readTree(json).isObject()).as("JSON-LD 파싱: %s", json).isTrue();
            blocks++;
        }
        assertThat(blocks).isPositive();
    }

    private static Model modelOf(Map<String, Object> attributes) {
        Model model = new ConcurrentModel();
        model.addAllAttributes(attributes);
        return model;
    }

    private static Corporation corporation() {
        return Corporation.builder().id(10L).name("NewCodes").homeLink("https://newcodes.net")
            .blogLink("https://newcodes.net/blog").isDomestic(1).build();
    }

    private static Article article(long id, String title, String summary) {
        return Article.builder().id(id).title(title).summary(summary).link("https://example.com/" + id)
            .corporation(corporation()).publishedAt(LocalDateTime.of(2026, 7, 22, 10, 9, 20)).build();
    }

    /** 비로그인 사용자 기준: sec:authorize 영역은 지우고 #authorization.expression(...)은 false. */
    static class AnonymousSecurityDialect extends AbstractProcessorDialect implements IExpressionObjectDialect {

        AnonymousSecurityDialect() {
            super("anonymous-security-stub", "sec", 1000);
        }

        @Override
        public Set<IProcessor> getProcessors(String dialectPrefix) {
            return Set.of(new AbstractAttributeTagProcessor(TemplateMode.HTML, dialectPrefix, null, false,
                "authorize", true, 1000, true) {
                @Override
                protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                                         AttributeName attributeName, String attributeValue,
                                         IElementTagStructureHandler structureHandler) {
                    structureHandler.removeElement();
                }
            });
        }

        @Override
        public IExpressionObjectFactory getExpressionObjectFactory() {
            return new IExpressionObjectFactory() {
                @Override
                public Set<String> getAllExpressionObjectNames() {
                    return Set.of("authorization");
                }

                @Override
                public Object buildObject(IExpressionContext context, String expressionObjectName) {
                    return new AnonymousAuthorization();
                }

                @Override
                public boolean isCacheable(String expressionObjectName) {
                    return true;
                }
            };
        }
    }

    public static class AnonymousAuthorization {
        public boolean expression(String expression) {
            return false;
        }
    }
}
