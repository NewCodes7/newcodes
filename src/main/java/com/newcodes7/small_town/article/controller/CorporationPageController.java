package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.exception.CorporationNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.corporation.dto.CorporationDetailDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.entity.Industry;
import com.newcodes7.small_town.corporation.repository.IndustryRepository;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.service.SearchLogService;
import com.newcodes7.small_town.video.repository.VideoRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * 기업 목록/상세 Thymeleaf 페이지 렌더링 컨트롤러.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class CorporationPageController {

    private static final int MAX_PAGE_SIZE = 50;

    @Value("${app.base-url}")
    private String baseUrl;

    private final ArticleService articleService;
    private final CorporationService corporationService;
    private final VideoRepository videoRepository;
    private final IndustryRepository industryRepository;
    private final SearchLogService searchLogService;
    private final com.newcodes7.small_town.auth.repository.UserRepository userRepository;

    @GetMapping("/corporations")
    public String corporations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String filter,
            @RequestParam(required = false) List<Integer> industries,
            @RequestParam(defaultValue = "popular") String sort,
            Model model) {

        // 음수 page·0 이하 size는 PageRequest.of에서 IllegalArgumentException → 500이 된다
        page = Math.max(0, page);
        size = clampPageSize(size);

        // 정렬 옵션 설정
        Sort sortOption;
        if ("popular".equals(sort)) {
            // 인기순: 조회수 내림차순, 이름 오름차순
            sortOption = Sort.by(
                Sort.Order.desc("viewCount"),
                Sort.Order.asc("name")
            );
        } else {
            // 이름순: 이름 오름차순
            sortOption = Sort.by(Sort.Order.asc("name"));
        }

        // 통합 필터링 메서드 호출
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(
                search, filter, industries, PageRequest.of(page, size, sortOption)
        );

        // Statistics
        long totalCorporations = corporationService.getTotalCorporationCount();
        long totalArticles = articleService.getTotalArticleCount();
        long totalVideos = videoRepository.countByDeletedAtIsNull();

        long domesticCount = totalCorporations;
        long overseasCount = 0;

        // Industry 목록 조회
        List<Industry> allIndustries = industryRepository.findAll();

        model.addAttribute("corporations", corporations);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", corporations.getTotalPages());
        model.addAttribute("totalElements", corporations.getTotalElements());
        model.addAttribute("hasNext", corporations.hasNext());
        model.addAttribute("hasPrevious", corporations.hasPrevious());
        model.addAttribute("currentSearch", search);
        model.addAttribute("currentFilter", filter);
        model.addAttribute("currentSort", sort);
        model.addAttribute("selectedIndustries", industries);
        model.addAttribute("allIndustries", allIndustries);

        // Statistics
        model.addAttribute("totalCorporations", totalCorporations);
        model.addAttribute("domesticCount", domesticCount);
        model.addAttribute("overseasCount", overseasCount);
        model.addAttribute("totalArticles", totalArticles);
        model.addAttribute("totalVideos", totalVideos);

        model.addAttribute("canonicalUrl", baseUrl + "/corporations");
        return "corporations";
    }

    @GetMapping("/corporations/{corporationId}")
    public String corporationDetail(@PathVariable Long corporationId,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "10") int size,
                                  @RequestParam(required = false) String keyword,
                                  @AuthenticationPrincipal UserDetails userDetails,
                                  HttpServletRequest request,
                                  Model model) {
        page = Math.max(0, page);
        size = clampPageSize(size);

        CorporationDetailDto corporation;
        try {
            corporation = articleService.getCorporationDetail(corporationId);
        } catch (CorporationNotFoundException | InvalidParameterException e) {
            // ArticleService는 article.exception 쪽 예외를 던진다(corporation.exception에 같은 이름의 클래스가 따로 있다).
            // 뷰 요청에서는 RestApiExceptionHandler가 ArticleException을 다시 던져 500이 됐다
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }

        // 검색 로그 저장 (keyword 파라미터가 있고 로그인한 경우)
        if (keyword != null && !keyword.trim().isEmpty() && userDetails != null) {
            User user = userRepository.findByUsernameAndDeletedAtIsNull(userDetails.getUsername()).orElse(null);
            if (user != null) {
                log.info("검색 로그 저장: userId={}, keyword={}, type=CORPORATION, targetId={}",
                    user.getId(), keyword.trim(), corporationId);
                searchLogService.logSearch(keyword.trim(), SearchLog.SearchType.CORPORATION, corporationId, user, request);
            }
        }

        Page<ArticleListResponseDto> articles = articleService.getArticlesByCorporation(corporationId, page, size);

        model.addAttribute("corporation", corporation);
        model.addAttribute("articles", articles);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("corporationIntro", corporationIntro(corporation, page == 0 ? articles : null));

        model.addAttribute("canonicalUrl", baseUrl + "/corporations/" + corporationId);
        return "corporation-detail";
    }

    /**
     * 기업 페이지 소개문(본문 + meta description 공용). "OO 기술 블로그"로 검색한 사람에게 이 페이지가
     * 무엇인지 답하는 고유 텍스트다. 최신 글 날짜는 0페이지(정규 URL)에서만 알 수 있어 그때만 넣는다.
     */
    static String corporationIntro(CorporationDetailDto corporation, Page<ArticleListResponseDto> firstPage) {
        StringBuilder intro = new StringBuilder()
            .append(corporation.getName()).append(" 기술 블로그에 올라온 글 ")
            .append(String.format("%,d", corporation.getArticleCount()))
            .append("개를 최신순으로 모았습니다.");
        if (firstPage != null && !firstPage.isEmpty()) {
            String publishedAt = firstPage.getContent().get(0).getPublishedAt();
            if (publishedAt != null && publishedAt.length() >= 10) {
                intro.append(" 가장 최근 글은 ").append(publishedAt, 0, 10).append("에 올라왔습니다.");
            }
        }
        return intro.append(" NewCodes가 매일 새 글을 수집하며, 원문은 ")
            .append(corporation.getName()).append(" 공식 기술 블로그에서 읽을 수 있습니다.")
            .toString();
    }

    private static int clampPageSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }
}
