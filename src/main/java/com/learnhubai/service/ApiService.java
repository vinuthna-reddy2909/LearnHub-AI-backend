package com.learnhubai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhubai.security.JwtService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiService {
    private static final List<String> ROLES = List.of("Student", "Instructor", "Reviewer", "Mentor", "Admin");
    private static final TypeReference<Map<String, Object>> OBJECT_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJECT_LIST_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    public ApiService(JdbcTemplate jdbc, ObjectMapper objectMapper, JwtService jwtService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
    }

    public Map<String, Object> findUserById(String id) {
        UUID userId = uuidOrNull(id);
        if (userId == null) return null;
        return findOne("SELECT id::text AS _id, name, email, password, role FROM users WHERE id = ?", userId);
    }

    public Map<String, Object> register(Map<String, Object> body) {
        String name = requiredText(body, "name");
        String email = requiredText(body, "email").toLowerCase();
        String password = requiredText(body, "password");
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ApiException(400, "Please include a valid email");
        }
        if (password.length() < 6) {
            throw new ApiException(400, "Please enter a password with 6 or more characters");
        }
        String role = Optional.ofNullable(body.get("role")).map(Object::toString).orElse("Student");
        if (!ROLES.contains(role) || "Admin".equals(role)) {
            throw new ApiException(400, "Invalid user role");
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id, name, email, password, role) VALUES (?, ?, ?, ?, ?)",
                    id, name, email, passwordEncoder.encode(password), role);
        } catch (DataIntegrityViolationException exception) {
            if (userExists(email)) throw new ApiException(400, "User already exists");
            throw exception;
        }
        return userResponse(findUserById(id.toString()), true);
    }

    public Map<String, Object> login(Map<String, Object> body) {
        String email = requiredText(body, "email").toLowerCase();
        String password = requiredText(body, "password");
        Map<String, Object> user = findOne(
                "SELECT id::text AS _id, name, email, password, role FROM users WHERE lower(email) = lower(?)",
                email);
        if (user == null || !passwordEncoder.matches(password, text(user.get("password")))) {
            throw new ApiException(401, "Invalid email or password");
        }
        return userResponse(user, true);
    }

    public Map<String, Object> profile(Map<String, Object> user) {
        return userResponse(user, false);
    }

    public List<Map<String, Object>> courses() {
        return jdbc.queryForList("""
                SELECT c.id::text AS _id, c.title, c.description, c.category, c.modules::text AS modules_json,
                       c.status, c.created_at AS "createdAt", c.updated_at AS "updatedAt",
                       c.instructor_id::text AS instructor_id, u.name AS instructor_name
                FROM courses c JOIN users u ON u.id = c.instructor_id
                ORDER BY c.created_at DESC
                """).stream().map(this::courseResponse).toList();
    }

    public Map<String, Object> courseById(String id) {
        UUID courseId = uuidOrNull(id);
        if (courseId == null) throw new ApiException(400, "Invalid course id");
        Map<String, Object> course = findOne("""
                SELECT c.id::text AS _id, c.title, c.description, c.category, c.modules::text AS modules_json,
                       c.status, c.created_at AS "createdAt", c.updated_at AS "updatedAt",
                       c.instructor_id::text AS instructor_id, u.name AS instructor_name
                FROM courses c JOIN users u ON u.id = c.instructor_id WHERE c.id = ?
                """, courseId);
        if (course == null) throw new ApiException(404, "Course not found");
        return courseResponse(course);
    }

    public Map<String, Object> createCourse(Map<String, Object> body, Map<String, Object> user) {
        UUID id = UUID.randomUUID();
        UUID instructorId = UUID.fromString(text(user.get("_id")));
        String modules = json(body.getOrDefault("modules", List.of()));
        jdbc.update("""
                INSERT INTO courses (id, title, description, category, instructor_id, modules)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, id, requiredText(body, "title"), requiredText(body, "description"),
                requiredText(body, "category"), instructorId, modules);
        return courseById(id.toString());
    }

    @Transactional
    public Map<String, Object> enroll(String courseId, Map<String, Object> user) {
        UUID courseUuid = uuidOrNull(courseId);
        if (courseUuid == null) throw new ApiException(400, "Invalid course id");
        if (!exists("SELECT EXISTS (SELECT 1 FROM courses WHERE id = ?)", courseUuid)) {
            throw new ApiException(404, "Course not found");
        }
        UUID userId = UUID.fromString(text(user.get("_id")));
        if (exists("SELECT EXISTS (SELECT 1 FROM enrollments WHERE user_id = ? AND course_id = ?)",
                userId, courseUuid)) {
            throw new ApiException(400, "Already enrolled in this course");
        }
        jdbc.update("INSERT INTO enrollments (id, user_id, course_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), userId, courseUuid);
        return Map.of("message", "Enrolled successfully");
    }

    public List<Map<String, Object>> quizzesForCourse(String courseId) {
        UUID courseUuid = uuidOrNull(courseId);
        if (courseUuid == null) throw new ApiException(400, "Invalid course id");
        return jdbc.query("""
                SELECT id::text AS _id, course_id::text AS course, title, questions::text AS questions_json,
                       passing_score AS "passingScore", created_at AS "createdAt", updated_at AS "updatedAt"
                FROM quizzes WHERE course_id = ? ORDER BY created_at
                """, (result, rowNum) -> {
            Map<String, Object> quiz = new LinkedHashMap<>();
            quiz.put("_id", result.getString("_id"));
            quiz.put("course", result.getString("course"));
            quiz.put("title", result.getString("title"));
            quiz.put("questions", sanitizeQuestions(readObjectList(result.getString("questions_json"))));
            quiz.put("passingScore", result.getInt("passingScore"));
            quiz.put("createdAt", result.getObject("createdAt"));
            quiz.put("updatedAt", result.getObject("updatedAt"));
            return quiz;
        }, courseUuid);
    }

    public Map<String, Object> submitQuiz(String quizId, Map<String, Object> body, Map<String, Object> user) {
        UUID quizUuid = uuidOrNull(quizId);
        if (quizUuid == null) throw new ApiException(400, "Invalid quiz id");
        Map<String, Object> quiz = findOne("""
                SELECT id::text AS _id, course_id::text AS course, title, questions::text AS questions_json,
                       passing_score AS "passingScore"
                FROM quizzes WHERE id = ?
                """, quizUuid);
        if (quiz == null) throw new ApiException(404, "Quiz not found");
        List<Map<String, Object>> questions = readObjectList(text(quiz.get("questions_json")));
        List<Map<String, Object>> answers = mapList(body.get("answers"));
        if (answers.isEmpty()) throw new ApiException(400, "Answers are required");

        List<Map<String, Object>> processedAnswers = new ArrayList<>();
        int totalCorrect = 0;
        for (Map<String, Object> answer : answers) {
            String questionId = text(answer.get("questionId"));
            Integer selected = integerValue(answer.get("selectedOptionIndex"));
            Map<String, Object> question = questions.stream()
                    .filter(item -> questionId.equals(questionId(item)))
                    .findFirst().orElse(null);
            if (question == null || selected == null) throw new ApiException(400, "Invalid quiz answer");
            boolean correct = selected.equals(integerValue(question.get("correctOptionIndex")));
            if (correct) totalCorrect++;
            processedAnswers.add(Map.of(
                    "questionId", questionId,
                    "selectedOptionIndex", selected,
                    "isCorrect", correct
            ));
        }
        if (questions.isEmpty()) throw new ApiException(400, "Quiz has no questions");

        double score = (double) totalCorrect / questions.size() * 100;
        double passingScore = Optional.ofNullable(integerValue(quiz.get("passingScore"))).orElse(70);
        boolean passed = score >= passingScore;
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quiz_attempts (id, user_id, quiz_id, course_id, answers, score, passed)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, attemptId, UUID.fromString(text(user.get("_id"))), quizUuid,
                UUID.fromString(text(quiz.get("course"))), json(processedAnswers), score, passed);
        return Map.of(
                "attemptId", attemptId.toString(),
                "score", score,
                "passed", passed,
                "totalCorrect", totalCorrect,
                "totalQuestions", questions.size()
        );
    }

    public Map<String, Object> recommendations(String attemptId) {
        UUID attemptUuid = uuidOrNull(attemptId);
        if (attemptUuid == null) throw new ApiException(400, "Invalid attempt id");
        Map<String, Object> attempt = findOne("""
                SELECT quiz_id::text AS quiz, answers::text AS answers_json, score, passed
                FROM quiz_attempts WHERE id = ?
                """, attemptUuid);
        if (attempt == null) throw new ApiException(404, "Quiz attempt not found");
        Map<String, Object> quiz = findOne("""
                SELECT title, questions::text AS questions_json FROM quizzes WHERE id = ?
                """, UUID.fromString(text(attempt.get("quiz"))));
        if (quiz == null) throw new ApiException(404, "Quiz not found");

        List<Map<String, Object>> questions = readObjectList(text(quiz.get("questions_json")));
        List<Map<String, Object>> answers = readObjectList(text(attempt.get("answers_json")));
        List<String> weakTopics = new ArrayList<>();
        List<String> strongTopics = new ArrayList<>();
        for (Map<String, Object> answer : answers) {
            questions.stream()
                    .filter(question -> text(answer.get("questionId")).equals(questionId(question)))
                    .findFirst()
                    .ifPresent(question -> (Boolean.TRUE.equals(answer.get("isCorrect"))
                            ? strongTopics : weakTopics).add(text(question.get("questionText"))));
        }
        return Map.of(
                "quizTitle", text(quiz.get("title")),
                "score", ((Number) attempt.get("score")).doubleValue(),
                "passed", Boolean.TRUE.equals(attempt.get("passed")),
                "weakTopics", weakTopics,
                "strongTopics", strongTopics
        );
    }

    public Map<String, Object> platformStats() {
        return Map.of(
                "users", count("users"),
                "courses", count("courses"),
                "quizAttempts", count("quiz_attempts")
        );
    }

    public Map<String, Object> instructorStats(Map<String, Object> user) {
        List<Map<String, Object>> engagements = jdbc.query("""
                SELECT c.title AS name, COUNT(e.id) AS students
                FROM courses c LEFT JOIN enrollments e ON e.course_id = c.id
                WHERE c.instructor_id = ?
                GROUP BY c.id, c.title ORDER BY c.created_at DESC
                """, (result, rowNum) -> Map.of(
                "name", result.getString("name"),
                "students", result.getLong("students")
        ), UUID.fromString(text(user.get("_id"))));
        return Map.of("courseEngagements", engagements);
    }

    private Map<String, Object> userResponse(Map<String, Object> user, boolean includeToken) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("_id", user.get("_id"));
        response.put("name", user.get("name"));
        response.put("email", user.get("email"));
        response.put("role", user.get("role"));
        if (includeToken) response.put("token", jwtService.generate(text(user.get("_id"))));
        return response;
    }

    private Map<String, Object> courseResponse(Map<String, Object> course) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("_id", course.get("_id"));
        response.put("title", course.get("title"));
        response.put("description", course.get("description"));
        response.put("category", course.get("category"));
        response.put("instructor", Map.of(
                "_id", course.get("instructor_id"),
                "name", course.get("instructor_name")
        ));
        response.put("modules", readObject(text(course.get("modules_json"))));
        response.put("enrolledStudents", jdbc.queryForList("""
                SELECT user_id::text AS id FROM enrollments WHERE course_id = ? ORDER BY created_at
                """, UUID.fromString(text(course.get("_id")))).stream()
                .map(row -> row.get("id")).toList());
        response.put("status", course.get("status"));
        response.put("createdAt", course.get("createdAt"));
        response.put("updatedAt", course.get("updatedAt"));
        return response;
    }

    private List<Map<String, Object>> sanitizeQuestions(List<Map<String, Object>> questions) {
        return questions.stream().map(question -> {
            Map<String, Object> safe = new LinkedHashMap<>(question);
            safe.remove("correctOptionIndex");
            safe.remove("explanation");
            return safe;
        }).toList();
    }

    private Map<String, Object> findOne(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean userExists(String email) {
        return exists("SELECT EXISTS (SELECT 1 FROM users WHERE lower(email) = lower(?))", email);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Object readObject(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON data stored in PostgreSQL", exception);
        }
    }

    private List<Map<String, Object>> readObjectList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, OBJECT_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid JSON data stored in PostgreSQL", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Request contains unsupported JSON data", exception);
        }
    }

    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance)
                .map(item -> castMap(item)).toList();
    }

    private static Map<String, Object> castMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, item) -> result.put(text(key), item));
        return result;
    }

    private static String questionId(Map<String, Object> question) {
        return text(question.containsKey("_id") ? question.get("_id") : question.get("id"));
    }

    private static UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static String requiredText(Map<String, Object> body, String field) {
        String value = text(body.get(field)).trim();
        if (value.isEmpty()) throw new ApiException(400, field + " is required");
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        return null;
    }
}
