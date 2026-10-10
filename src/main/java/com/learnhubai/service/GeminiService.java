package com.learnhubai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GeminiService {
    private final String apiKey;
    private final RestClient restClient = RestClient.create();
    private final ObjectMapper objectMapper;

    public GeminiService(@Value("${learnhub.gemini-api-key:}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> recommendations(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        List<String> weakTopics = (List<String>) input.get("weakTopics");
        @SuppressWarnings("unchecked")
        List<String> strongTopics = (List<String>) input.get("strongTopics");
        if (apiKey.isBlank() || apiKey.equals("your_gemini_api_key_here")) {
            return Map.of(
                    "topicBreakdown", List.of(
                            Map.of("topic", firstOr(weakTopics, "Sample Weak Topic"), "score", 40, "status", "weak"),
                            Map.of("topic", firstOr(strongTopics, "Sample Strong Topic"), "score", 90, "status", "strong")
                    ),
                    "recommendedPath", List.of(
                            "Review the weak concepts listed above.",
                            "Practice the specific interactive exercises.",
                            "Retake the quiz to validate your understanding.",
                            "Move onto the next module."
                    )
            );
        }

        String prompt = """
                You are an expert AI tutor for the LearnHub AI platform.
                A student took the quiz "%s".
                Score: %s%% (%s).
                Topics they struggled with: %s.
                Topics they excelled at: %s.
                Return valid JSON with exactly two keys: "topicBreakdown" (array of objects with "topic" string,
                "score" number, "status" string: strong, moderate, or weak) and "recommendedPath"
                (array of 3-4 actionable sequential strings). Do not include markdown.
                """.formatted(
                input.get("quizTitle"),
                input.get("score"),
                Boolean.TRUE.equals(input.get("passed")) ? "Passed" : "Failed",
                String.join(", ", weakTopics.stream().limit(5).toList()),
                String.join(", ", strongTopics.stream().limit(5).toList())
        );
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json")
        );
        try {
            Map<?, ?> response = restClient.post()
                    .uri("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={key}", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);
            if (response == null) throw new IllegalStateException("Gemini returned an empty response");
            List<?> candidates = (List<?>) response.get("candidates");
            Map<?, ?> candidate = (Map<?, ?>) candidates.getFirst();
            Map<?, ?> content = (Map<?, ?>) candidate.get("content");
            List<?> parts = (List<?>) content.get("parts");
            Map<?, ?> part = (Map<?, ?>) parts.getFirst();
            String text = part.get("text").toString();
            return objectMapper.readValue(text, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new ApiException(502, "Failed to generate AI recommendations");
        }
    }

    private static String firstOr(List<String> topics, String fallback) {
        return topics == null || topics.isEmpty() ? fallback : topics.getFirst();
    }
}
