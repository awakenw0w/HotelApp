## запуск апи
```powershell
mvn spring-boot:run
```

## Адреса

- `GET http://localhost:8080/api/rooms` - номера, тип, категория и последний сохранённый статус.
- `GET http://localhost:8080/api/guests` - гости.
- `GET http://localhost:8080/api/stays` - все проживания.
- `GET http://localhost:8080/api/stays?from=2025-02-01&to=2025-02-28` - проживания, пересекающиеся с указанным периодом.

## пользователи
Администратор: admin / Admin2026!
Пользователь: user / User2026!