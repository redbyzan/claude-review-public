package com.review.server.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

@Service
public class ChangelogService {

    private static final Logger log = LoggerFactory.getLogger(ChangelogService.class);

    @Value("${review.changelog.show-notice:false}")
    private boolean showNotice;

    @Value("${review.server-url:https://review.example.com}")
    private String serverUrl;

    private List<ChangelogEntry> entries = List.of();

    public record ChangelogEntry(String date, String slug, String title,
                                  String description, String detail, String action, String actionUrl,
                                  String category, boolean internal) {
        public String anchor() {
            return date + "-" + slug;
        }

        public boolean isNew() {
            LocalDate entryDate = LocalDate.parse(date);
            return !entryDate.isBefore(LocalDate.now().minusDays(7));
        }
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        try {
            var resource = new ClassPathResource("changelog.yml");
            if (!resource.exists()) {
                log.info("[Changelog] changelog.yml 없음 — changelog 기능 비활성화");
                return;
            }

            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(content);

            List<Map<String, Object>> raw = (List<Map<String, Object>>) data.get("entries");
            if (raw == null || raw.isEmpty()) {
                log.info("[Changelog] entries 없음");
                return;
            }

            entries = raw.stream()
                .map(m -> new ChangelogEntry(
                    String.valueOf(m.getOrDefault("date", "")),
                    String.valueOf(m.getOrDefault("slug", "")),
                    String.valueOf(m.getOrDefault("title", "")),
                    String.valueOf(m.getOrDefault("description", "")),
                    String.valueOf(m.getOrDefault("detail", "")),
                    String.valueOf(m.getOrDefault("action", "")),
                    String.valueOf(m.getOrDefault("action-url", "")),
                    String.valueOf(m.getOrDefault("category", "improvement")),
                    Boolean.TRUE.equals(m.get("internal"))))
                .toList();

            log.info("[Changelog] {}개 항목 로드 완료 | show-notice: {}", entries.size(), showNotice);
        } catch (IOException e) {
            log.warn("[Changelog] 로드 실패: {}", e.getMessage());
        }
    }

    public boolean shouldShowNotice() {
        return showNotice && !entries.isEmpty();
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getNoticeMarkdown() {
        if (!shouldShowNotice()) return null;
        ChangelogEntry latest = getPublicEntries().isEmpty() ? null : getPublicEntries().get(0);
        if (latest == null) return null;
        return String.format(
            "**[업데이트 확인](%s/release#%s)** — %s",
            serverUrl, latest.anchor(), latest.title()
        );
    }

    public List<ChangelogEntry> getEntries() {
        return entries;
    }

    public List<ChangelogEntry> getPublicEntries() {
        return entries.stream().filter(e -> !e.internal()).toList();
    }

    public List<ChangelogEntry> getRecentPublicEntries(int n) {
        return getPublicEntries().stream().limit(n).toList();
    }

    public ChangelogEntry getPublicEntry(String slug) {
        return getPublicEntries().stream()
            .filter(e -> e.slug().equals(slug))
            .findFirst()
            .orElse(null);
    }
}
