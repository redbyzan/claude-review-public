package com.review.server.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

// @MX:NOTE: 모델별 토큰 단가 카탈로그 (USD per 1M tokens). 미확인 모델은 기본 단가(DEFAULT) 적용.
@Component
public class PricingCatalog {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    // @MX:NOTE: 카탈로그에 없는 모델에 적용할 기본 단가 (Gemini 2.0 Flash 기준)
    private static final ModelPricing DEFAULT_PRICING = new ModelPricing("0.10", "0.40");

    // @MX:NOTE: 긴 접두사 먼저 매칭 — Map.ofEntries()는 순서 미보장이므로 생성자에서 순차 put
    private final Map<String, ModelPricing> prices;

    public PricingCatalog() {
        prices = new LinkedHashMap<>();
        // Claude models
        prices.put("claude-sonnet-4-20250514", new ModelPricing("3.00", "15.00"));
        prices.put("claude-sonnet-4-5-20250514", new ModelPricing("3.00", "15.00"));
        prices.put("claude-3-5-sonnet", new ModelPricing("3.00", "15.00"));
        prices.put("claude-haiku-4-5-20251001", new ModelPricing("1.00", "5.00"));
        // Gemini (Google) — 공식 가격: https://ai.google.dev/pricing
        prices.put("gemini-2.5-flash", new ModelPricing("0.15", "0.60"));
        prices.put("gemini-2.5-pro", new ModelPricing("1.25", "10.00"));
        prices.put("gemini-2.0-flash-lite", new ModelPricing("0.075", "0.30"));
        prices.put("gemini-2.0-flash", new ModelPricing("0.10", "0.40"));
        prices.put("gemini-1.5-flash-8b", new ModelPricing("0.0375", "0.15"));
        prices.put("gemini-1.5-flash", new ModelPricing("0.075", "0.30"));
        prices.put("gemini-1.5-pro", new ModelPricing("1.25", "5.00"));
        prices.put("gemini-1.0-pro", new ModelPricing("0.50", "1.50"));
        prices.put("gemini", new ModelPricing("0.10", "0.40"));
        // GLM (Zhipu) — 추정 단가, 실제 비용은 계약에 따라 다름
        prices.put("glm", new ModelPricing("0.10", "0.40"));
    }

    public record ModelPricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
        ModelPricing(String input, String output) {
            this(new BigDecimal(input), new BigDecimal(output));
        }
    }

    public Optional<ModelPricing> getPricing(String model) {
        if (model == null || model.isEmpty() || "null".equals(model)) return Optional.empty();
        // 접두사 매칭: "claude-sonnet-4-20250514-001" 같은 변형 처리
        for (var entry : prices.entrySet()) {
            if (model.startsWith(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        // @MX:NOTE: 카탈로그에 없는 모델은 기본 단가 적용 (비용 0으로 표시 방지)
        return Optional.of(DEFAULT_PRICING);
    }

    public BigDecimal calculateCost(String model, long inputTokens, long outputTokens) {
        return getPricing(model)
            .map(p -> {
                BigDecimal inCost = p.inputPerMillion()
                    .multiply(BigDecimal.valueOf(inputTokens))
                    .divide(MILLION, 6, java.math.RoundingMode.HALF_UP);
                BigDecimal outCost = p.outputPerMillion()
                    .multiply(BigDecimal.valueOf(outputTokens))
                    .divide(MILLION, 6, java.math.RoundingMode.HALF_UP);
                return inCost.add(outCost);
            })
            .orElse(BigDecimal.ZERO);
    }
}
