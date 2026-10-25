package ru.hotel.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Вход, CAPTCHA и простое управление пользователями. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final JdbcTemplate db;
    private final Map<String, List<Integer>> puzzles = new ConcurrentHashMap<>();

    public AuthController(JdbcTemplate db) {
        this.db = db;
    }

    /** Создаёт CAPTCHA из четырёх фрагментов, которые нужно собрать по порядку. */
    @GetMapping("/auth/captcha")
    public Map<String, Object> captcha() {
        List<Integer> pieces = new ArrayList<>(List.of(1, 2, 3, 4));
        java.util.Collections.shuffle(pieces);
        String id = UUID.randomUUID().toString();
        puzzles.put(id, List.of(1, 2, 3, 4));
        return Map.of("id", id, "pieces", pieces);
    }

    /** Проверяет пароль и пазл; три неверные попытки блокируют пользователя. */
    @PostMapping("/auth/login")
    public Map<String, String> login(@RequestBody Map<String, Object> data, HttpSession session) {
        String login = String.valueOf(data.getOrDefault("login", ""));
        String password = String.valueOf(data.getOrDefault("password", ""));
        String puzzleId = String.valueOf(data.getOrDefault("puzzleId", ""));
        List<?> answer = data.get("answer") instanceof List<?> list ? list : List.of();
        List<Integer> expected = puzzles.remove(puzzleId);

        List<Map<String, Object>> rows = db.queryForList("""
                SELECT user_id AS id, login, password,
                       role, failed_attempts AS failures, is_blocked AS blocked
                FROM users WHERE login = ?
                """, login);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");

        Map<String, Object> user = rows.get(0);
        if (Boolean.TRUE.equals(user.get("blocked"))) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "Вы заблокированы. Обратитесь к администратору");
        }

        if (!password.equals(user.get("password"))) {
            fail(user, "Вы ввели неверный логин или пароль. Пожалуйста проверьте ещё раз введенные данные");
        }
        if (expected == null || !expected.equals(answer)) {
            fail(user, "Неправильно собрана CAPTCHA");
        }

        db.update("UPDATE users SET failed_attempts = 0 WHERE user_id = ?", user.get("id"));
        session.setAttribute("userId", user.get("id"));
        session.setAttribute("login", login);
        session.setAttribute("role", user.get("role"));
        return Map.of("message", "Вы успешно авторизовались", "login", login, "role", (String) user.get("role"));
    }

    /** Завершает вход пользователя. */
    @PostMapping("/auth/logout")
    public Map<String, String> logout(HttpSession session) {
        session.invalidate();
        return Map.of("message", "Вы вышли из системы");
    }

    /** Показывает администратору список учётных записей без хешей паролей. */
    @GetMapping("/users")
    public List<Map<String, Object>> users(HttpSession session) {
        adminOnly(session);
        return db.queryForList("SELECT user_id AS id, login, role, is_blocked AS blocked FROM users ORDER BY login");
    }

    /** Создаёт учётную запись, сохраняя введённый пароль в таблице users. */
    @PostMapping("/users")
    public Map<String, String> addUser(@RequestBody Map<String, String> data, HttpSession session) {
        adminOnly(session);
        String login = data.get("login");
        String password = data.get("password");
        String role = data.get("role");
        if (login == null || password == null || role == null || login.isBlank() || password.isBlank()
                || !(role.equals("ADMIN") || role.equals("USER"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Проверьте логин, пароль и роль");
        }
        try {
            db.update("INSERT INTO users (login, password, role) VALUES (?, ?, ?)",
                    login, password, role);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Такой логин уже существует");
        }
        return Map.of("message", "Пользователь создан");
    }

    /** Меняет логин, роль и при необходимости пароль пользователя. */
    @PutMapping("/users/{id}")
    public Map<String, String> editUser(@PathVariable int id, @RequestBody Map<String, String> data,
                                       HttpSession session) {
        adminOnly(session);
        String login = data.get("login");
        String role = data.get("role");
        if (login == null || role == null || !(role.equals("ADMIN") || role.equals("USER"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Проверьте логин и роль");
        }
        int found = db.update("UPDATE users SET login = ?, role = ? WHERE user_id = ?", login, role, id);
        if (found == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден");
        String password = data.get("password");
        if (password != null && !password.isBlank()) {
            db.update("UPDATE users SET password = ? WHERE user_id = ?", password, id);
        }
        return Map.of("message", "Пользователь изменён");
    }

    /** Снимает блокировку после обращения к администратору. */
    @PatchMapping("/users/{id}/unlock")
    public Map<String, String> unlock(@PathVariable int id, HttpSession session) {
        adminOnly(session);
        db.update("UPDATE users SET failed_attempts = 0, is_blocked = FALSE WHERE user_id = ?", id);
        return Map.of("message", "Пользователь разблокирован");
    }

    /** Проверяет, что запрос сделал вошедший администратор. */
    private void adminOnly(HttpSession session) {
        if (session.getAttribute("userId") == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сначала войдите в систему");
        }
        if (!"ADMIN".equals(session.getAttribute("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ только для администратора");
        }
    }

    /** Учитывает неверный пароль или собранную CAPTCHA и блокирует после трёх ошибок. */
    private void fail(Map<String, Object> user, String message) {
        int failures = ((Number) user.get("failures")).intValue() + 1;
        boolean blocked = failures >= 3;
        db.update("UPDATE users SET failed_attempts = ?, is_blocked = ? WHERE user_id = ?",
                failures, blocked, user.get("id"));
        if (blocked) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "Вы заблокированы. Обратитесь к администратору");
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
    }
}
