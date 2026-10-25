package ru.hotel.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Принимает запросы от приложения и читает данные гостиницы из MySQL. */
@RestController
@RequestMapping("/api")
public class HotelController {

    private final JdbcTemplate jdbcTemplate;

    /** Spring передаёт сюда готовый инструмент для SQL-запросов. */
    public HotelController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Возвращает номера вместе с типом, категорией и последним известным статусом. */
    @GetMapping("/rooms")
    public List<Map<String, Object>> getRooms(HttpSession session) {
        requireLogin(session);

        String sql = """
                SELECT
                    r.room_id AS roomId,
                    r.room_number AS roomNumber,
                    r.floor_number AS floorNumber,
                    rt.room_type_id AS roomTypeId,
                    rt.guest_capacity AS guestCapacity,
                    rt.room_layout AS roomLayout,
                    rc.category_name AS categoryName,
                    latest.report_date AS statusDate,
                    os.status_name AS occupancyStatus,
                    hs.status_name AS housekeepingStatus
                FROM rooms r
                JOIN room_types rt ON rt.room_type_id = r.room_type_id
                JOIN room_categories rc ON rc.room_category_id = rt.room_category_id
                LEFT JOIN (
                    SELECT room_id, MAX(report_date) AS report_date
                    FROM room_status_history
                    GROUP BY room_id
                ) latest ON latest.room_id = r.room_id
                LEFT JOIN room_status_history history
                    ON history.room_id = latest.room_id
                    AND history.report_date = latest.report_date
                LEFT JOIN occupancy_statuses os
                    ON os.occupancy_status_id = history.occupancy_status_id
                LEFT JOIN housekeeping_statuses hs
                    ON hs.housekeeping_status_id = history.housekeeping_status_id
                ORDER BY r.room_number
                """;

        return jdbcTemplate.queryForList(sql);
    }

    /** Возвращает список гостей, отсортированный по фамилии и имени. */
    @GetMapping("/guests")
    public List<Map<String, Object>> getGuests(HttpSession session) {
        requireLogin(session);

        String sql = """
                SELECT
                    guest_id AS guestId,
                    last_name AS lastName,
                    first_name AS firstName,
                    patronymic AS patronymic
                FROM guests
                ORDER BY last_name, first_name
                """;

        return jdbcTemplate.queryForList(sql);
    }

    /** Возвращает проживания с данными гостя и номером комнаты.
     * Если передать from и to, список будет ограничен этим периодом.
     */
    @GetMapping("/stays")
    public List<Map<String, Object>> getStays(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            HttpSession session) {

        requireLogin(session);

        String sqlWithoutDates = """
                SELECT
                    s.stay_id AS stayId,
                    g.guest_id AS guestId,
                    CONCAT_WS(' ', g.last_name, g.first_name, g.patronymic) AS guestName,
                    r.room_number AS roomNumber,
                    s.check_in_date AS checkInDate,
                    s.check_out_date AS checkOutDate
                FROM stays s
                JOIN guests g ON g.guest_id = s.guest_id
                JOIN rooms r ON r.room_id = s.room_id
                ORDER BY s.check_in_date, s.stay_id
                """;

        // Если даты не переданы, возвращаем все проживания.
        if (from == null && to == null) {
            return jdbcTemplate.queryForList(sqlWithoutDates);
        }

        // Для поиска за период пользователь должен указать обе даты.
        if (from == null || to == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Укажите обе даты: from и to в формате YYYY-MM-DD"
            );
        }

        LocalDate fromDate;
        LocalDate toDate;

        // Преобразуем текстовые параметры в даты и проверяем формат.
        try {
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Дата должна быть в формате YYYY-MM-DD"
            );
        }

        if (fromDate.isAfter(toDate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Дата from не должна быть позже даты to"
            );
        }

        String sqlForDates = """
                SELECT
                    s.stay_id AS stayId,
                    g.guest_id AS guestId,
                    CONCAT_WS(' ', g.last_name, g.first_name, g.patronymic) AS guestName,
                    r.room_number AS roomNumber,
                    s.check_in_date AS checkInDate,
                    s.check_out_date AS checkOutDate
                FROM stays s
                JOIN guests g ON g.guest_id = s.guest_id
                JOIN rooms r ON r.room_id = s.room_id
                WHERE s.check_in_date <= ?
                  AND (s.check_out_date IS NULL OR s.check_out_date > ?)
                ORDER BY s.check_in_date, s.stay_id
                """;

        return jdbcTemplate.queryForList(sqlForDates, toDate, fromDate);
    }

    /** Не отдаёт данные гостиницы до входа в систему. */
    private void requireLogin(HttpSession session) {
        if (session.getAttribute("userId") == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Сначала войдите в систему");
        }
    }
}
