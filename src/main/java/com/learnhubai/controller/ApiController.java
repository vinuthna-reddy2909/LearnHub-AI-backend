package com.learnhubai.controller;

import com.learnhubai.service.ApiException;
import com.learnhubai.service.ApiService;
import com.learnhubai.service.GeminiService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApiController {
    private final ApiService apiService;
    private final GeminiService geminiService;

    public ApiController(ApiService apiService, GeminiService geminiService) {
        this.apiService = apiService;
        this.geminiService = geminiService;
    }

    @GetMapping("/")
    public Map<String, String> health() {
        return Map.of("status", "ok", "message", "LearnHub AI API is running ✅");
    }

    @PostMapping("/api/auth/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiService.register(body));
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        return apiService.login(body);
    }

    @GetMapping("/api/auth/profile")
    public Map<String, Object> profile(HttpServletRequest request) {
        return apiService.profile(currentUser(request));
    }

    @GetMapping("/api/courses")
    public List<Map<String, Object>> courses() {
        return apiService.courses();
    }

    @GetMapping("/api/courses/{id}")
    public Map<String, Object> course(@PathVariable String id) {
        return apiService.courseById(id);
    }

    @PostMapping("/api/courses")
    public ResponseEntity<Map<String, Object>> createCourse(@RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        Map<String, Object> user = currentUser(request);
        requireRole(user, "Instructor", "Admin");
        return ResponseEntity.status(HttpStatus.CREATED).body(apiService.createCourse(body, user));
    }

    @PostMapping("/api/courses/{id}/enroll")
    public Map<String, Object> enroll(@PathVariable String id, HttpServletRequest request) {
        return apiService.enroll(id, currentUser(request));
    }

    @GetMapping("/api/quizzes/{courseId}")
    public List<Map<String, Object>> quizzes(@PathVariable String courseId) {
        return apiService.quizzesForCourse(courseId);
    }

    @PostMapping("/api/quizzes/{id}/submit")
    public ResponseEntity<Map<String, Object>> submitQuiz(@PathVariable String id,
                                                           @RequestBody Map<String, Object> body,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(apiService.submitQuiz(id, body, currentUser(request)));
    }

    @GetMapping("/api/ai/recommendations/{attemptId}")
    public Map<String, Object> recommendations(@PathVariable String attemptId) {
        return geminiService.recommendations(apiService.recommendations(attemptId));
    }

    @GetMapping("/api/analytics/platform")
    public Map<String, Object> platformStats(HttpServletRequest request) {
        requireRole(currentUser(request), "Admin");
        return apiService.platformStats();
    }

    @GetMapping("/api/analytics/instructor")
    public Map<String, Object> instructorStats(HttpServletRequest request) {
        Map<String, Object> user = currentUser(request);
        requireRole(user, "Instructor");
        return apiService.instructorStats(user);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> currentUser(HttpServletRequest request) {
        Object user = request.getAttribute("authenticatedUser");
        if (!(user instanceof Map<?, ?>)) {
            throw new ApiException(401, "Not authorized, no token");
        }
        return (Map<String, Object>) user;
    }

    private static void requireRole(Map<String, Object> user, String... roles) {
        String role = user.get("role") == null ? null : user.get("role").toString();
        for (String allowed : roles) {
            if (allowed.equals(role)) return;
        }
        throw new ApiException(403, "User role " + (role == null ? "Unknown" : role)
                + " is not authorized to access this route");
    }
}
