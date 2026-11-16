package com.newcodes7.small_town.global.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.ArticleIndexPolicy;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.theme.entity.Theme;
import com.newcodes7.small_town.theme.repository.ThemeRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SitemapControllerTest {

    private ArticleRepository articleRepository;
    private ThemeRepository themeRepository;

    @BeforeEach
    void setUp() {
        articleRepository = mock(ArticleRepository.class);
        themeRepository = mock(ThemeRepository.class);
        Object[] indexable = {1L, "요약 있는 글", null, LocalDateTime.of(2026, 7, 22, 10, 0)};
        List<Object[]> indexableRows = List.<Object[]>of(indexable);
        when(articleRepository.findIndexableArticlesForSitemap()).thenReturn(indexableRows);
        when(themeRepository.findByIsActiveTrueAndDeletedAtIsNullOrderByDisplayOrderAsc())
            .thenReturn(List.of(Theme.builder().id(5L).name("신입").build()));
    }

    private SitemapController controller(boolean indexRequiresSummary) {
        SitemapController controller = new SitemapController(articleRepository, mock(CorporationRepository.class),
            new ArticleIndexPolicy(indexRequiresSummary), themeRepository);
        ReflectionTestUtils.setField(controller, "baseUrl", "https://newcodes.net");
        return controller;
    }

    @Test
    @DisplayName("요약 있는 글만 싣고, 테마·소개 페이지를 포함한다")
    void 색인대상만_테마_소개_포함() {
        String xml = controller(true).sitemap().getBody();

        verify(articleRepository, never()).findAllActiveArticlesForSitemap();
        assertThat(xml).contains("<loc>https://newcodes.net/articles/1-요약-있는-글</loc>".replace(
            "요약-있는-글", java.net.URLEncoder.encode("요약-있는-글", java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(xml).contains("<loc>https://newcodes.net/themes</loc>");
        assertThat(xml).contains("<loc>https://newcodes.net/themes/5</loc>");
        assertThat(xml).contains("<loc>https://newcodes.net/about</loc>");
    }

    @Test
    @DisplayName("정책을 끄면 이전처럼 전체 글을 싣는다")
    void 정책off_전체글() {
        controller(false).sitemap();

        verify(articleRepository).findAllActiveArticlesForSitemap();
        verify(articleRepository, never()).findIndexableArticlesForSitemap();
    }
}
