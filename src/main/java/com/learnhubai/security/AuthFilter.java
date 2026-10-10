package com.learnhubai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhubai.service.ApiService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import io.jsonwebtoken.JwtException;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final ApiService apiService;
    private final ObjectMapper objectMapper;

    public AuthFilter(JwtService jwtService, ApiService apiService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.apiService = apiService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!requiresAuthentication(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeUnauthorized(response, "Not authorized, no token");
            return;
        }

        String userId;
        try {
            userId = jwtService.getUserId(authorization.substring(7));
        } catch (JwtException | IllegalArgumentException exception) {
            writeUnauthorized(response, "Not authorized, token failed");
            return;
        }

        Map<String, Object> user = apiService.findUserById(userId);
        if (user == null) {
            writeUnauthorized(response, "Not authorized, token failed");
            return;
        }
        request.setAttribute("authenticatedUser", user);
        filterChain.doFilter(request, response);
    }

    private boolean requiresAuthentication(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equals(method) && "/api/auth/profile".equals(path)) return true;
        if ("POST".equals(method) && "/api/courses".equals(path)) return true;
        if ("POST".equals(method) && path.matches("/api/courses/[^/]+/enroll")) return true;
        if ("POST".equals(method) && path.matches("/api/quizzes/[^/]+/submit")) return true;
        if ("GET".equals(method) && path.matches("/api/ai/recommendations/[^/]+")) return true;
        if ("GET".equals(method) && ("/api/analytics/platform".equals(path)
                || "/api/analytics/instructor".equals(path))) return true;
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
