package com.newcodes7.small_town.article.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.corporation.exception.CorporationNotFoundException;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

/**
 * 기업 목록/상세 페이지가 잘못된 입력에 500 대신 정상 응답 또는 404를 내는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CorporationPageControllerTest {

    @Mock
    private ArticleService articleService;

    @Mock
    private CorporationService corporationService;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private IndustryRepository industryRepository;

    @InjectMocks
    private CorporationPageController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "baseUrl", "https://newcodes.net");
    }

    @Test
    @DisplayName("corporations: 음수 page와 0 size는 PageRequest 예외(500) 대신 0페이지·최소 크기로 보정")
    void corporations_잘못된페이지_보정() {
        when(corporationService.getCorporationsWithFilters(isNull(), eq("all"), isNull(), any(PageRequest.class)))
            .thenReturn(Page.empty());
        Model model = new ConcurrentModel();

        String view = controller.corporations(-1, 0, null, "all", null, "popular", model);

        ArgumentCaptor<PageRequest> pageRequest = ArgumentCaptor.forClass(PageRequest.class);
        verify(corporationService).getCorporationsWithFilters(isNull(), eq("all"), isNull(), pageRequest.capture());
        assertThat(view).isEqualTo("corporations");
        assertThat(pageRequest.getValue().getPageNumber()).isZero();
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(1);
        assertThat(model.getAttribute("currentPage")).isEqualTo(0);
    }

    @Test
    @DisplayName("corporations: size 상한은 50")
    void corporations_size상한() {
        when(corporationService.getCorporationsWithFilters(isNull(), eq("all"), isNull(), any(PageRequest.class)))
            .thenReturn(Page.empty());

        controller.corporations(0, 100_000, null, "all", null, "popular", new ConcurrentModel());

        ArgumentCaptor<PageRequest> pageRequest = ArgumentCaptor.forClass(PageRequest.class);
        verify(corporationService).getCorporationsWithFilters(isNull(), eq("all"), isNull(), pageRequest.capture());
        assertThat(pageRequest.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("corporationDetail: 없는 기업은 500이 아니라 404")
    void corporationDetail_없는기업_404() {
        when(articleService.getCorporationDetail(99999L)).thenThrow(new CorporationNotFoundException(99999L));

        assertThatThrownBy(() -> controller.corporationDetail(99999L, 0, 10, null, null,
                new MockHttpServletRequest(), new ConcurrentModel()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
