package com.newcodes7.small_town.theme.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.theme.service.ThemeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ThemeControllerTest {

    @Mock
    private ThemeService themeService;

    @InjectMocks
    private ThemeController controller;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "baseUrl", "https://newcodes.net");
    }

    @Test
    @DisplayName("themes: 목록 페이지에 canonical URL을 넣는다")
    void themes_canonical() {
        when(themeService.getActiveThemes()).thenReturn(List.of());
        Model model = new ConcurrentModel();

        String view = controller.themes(model);

        assertThat(view).isEqualTo("themes");
        assertThat(model.getAttribute("canonicalUrl")).isEqualTo("https://newcodes.net/themes");
    }

    @Test
    @DisplayName("themeDetail: 없는 테마는 500이 아니라 404")
    void themeDetail_없는테마_404() {
        when(themeService.getThemeById(99999L))
            .thenThrow(new IllegalArgumentException("테마를 찾을 수 없습니다: 99999"));

        assertThatThrownBy(() -> controller.themeDetail(99999L, null, null,
                new MockHttpServletRequest(), new ConcurrentModel()))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
