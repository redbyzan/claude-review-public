package com.review.server.controller;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import com.review.server.prompt.PromptProvider;
import com.review.server.service.ChangelogService;
import com.review.server.service.LeaderboardService;
import com.review.server.service.SlackService;
import com.review.server.service.StatsService;

@Controller
public class HomeController {

    private final PromptProvider promptProvider;
    private final SlackService slackService;
    private final ChangelogService changelogService;
    private final LeaderboardService leaderboardService;
    private final StatsService statsService;
    private final Set<String> releaseTemplates;

    public HomeController(PromptProvider promptProvider, SlackService slackService,
                          ChangelogService changelogService,
                          LeaderboardService leaderboardService,
                          StatsService statsService,
                          @Value("${spring.thymeleaf.prefix:classpath:/templates/}") String templatePrefix) {
        this.promptProvider = promptProvider;
        this.slackService = slackService;
        this.changelogService = changelogService;
        this.leaderboardService = leaderboardService;
        this.statsService = statsService;
        this.releaseTemplates = scanReleaseTemplates(templatePrefix);
    }

    private static Set<String> scanReleaseTemplates(String prefix) {
        var resolver = new PathMatchingResourcePatternResolver();
        String location = prefix.startsWith("classpath:") ? prefix + "release-*.html" : "classpath:/templates/release-*.html";
        try {
            Resource[] resources = resolver.getResources(location);
            return Stream.of(resources)
                .map(r -> {
                    String name = r.getFilename();
                    return name != null ? name.replace(".html", "") : "";
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            return Set.of();
        }
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("personas", promptProvider.getPersonaSummaries());
        model.addAttribute("domain", "review.example.com");
        model.addAttribute("slackEnabled", slackService.isEnabled());
        model.addAttribute("changelog", changelogService.getRecentPublicEntries(3));
        model.addAttribute("hasMoreChangelog",
            changelogService.getPublicEntries().size() > 3);
        model.addAttribute("topTeams", leaderboardService.getTopTeams(3));
        model.addAttribute("leaderboardStats", leaderboardService.getTotalStats());
        return "index";
    }

    @GetMapping("/release")
    public String release(Model model) {
        model.addAttribute("domain", "review.example.com");
        model.addAttribute("changelog", changelogService.getPublicEntries());
        return "release";
    }

    @GetMapping("/release/{slug}")
    public String releaseDetail(@PathVariable String slug, Model model) {
        var entry = changelogService.getPublicEntry(slug);
        if (entry == null || entry.detail() == null || entry.detail().isBlank()) {
            return "redirect:/release";
        }
        model.addAttribute("entry", entry);
        model.addAttribute("domain", "review.example.com");

        // slug별 전용 템플릿이 있으면 사용, 없으면 기본 release-detail
        String customTemplate = "release-" + slug;
        if (releaseTemplates.contains(customTemplate)) {
            return customTemplate;
        }
        return "release-detail";
    }

    @GetMapping("/incident")
    public String incident() {
        return "incident";
    }

    @GetMapping("/incident/epic19")
    public String incidentEpic19() {
        return "incident-epic19";
    }

    @GetMapping("/incident/cloudflared-crash")
    public String incidentCloudflaredCrash() {
        return "incident-cloudflared-crash";
    }

    @GetMapping("/guide/workflow")
    public String guideWorkflow(Model model) {
        model.addAttribute("domain", "review.example.com");
        return "guide-workflow";
    }

    @GetMapping("/leaderboard")
    public String leaderboard(Model model) {
        model.addAttribute("teams", leaderboardService.getLeaderboard());
        model.addAttribute("totalStats", leaderboardService.getTotalStats());
        return "leaderboard";
    }

    @GetMapping("/stats")
    public String stats(Model model) {
        model.addAttribute("stats", statsService.getSnapshot());
        return "stats";
    }
}
