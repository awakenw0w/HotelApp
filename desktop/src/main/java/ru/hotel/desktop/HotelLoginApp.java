package ru.hotel.desktop;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import javafx.stage.Stage;

/** Простое окно входа; сервер проверяет пароль и собранную CAPTCHA. */
public class HotelLoginApp extends Application {

    private final String api = "http://localhost:8080/api";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    private final List<Integer> answer = new ArrayList<>();
    private final int[] pieces = new int[4];

    private Stage window;
    private TextField login;
    private PasswordField password;
    private final StackPane[] slots = new StackPane[4];
    private Label message;
    private String puzzleId;

    /** Открывает форму авторизации при запуске программы. */
    @Override
    public void start(Stage stage) {
        window = stage;
        showLogin();
    }

    /** Показывает поля входа и перемешанную картинку из четырёх частей. */
    private void showLogin() {
        login = new TextField();
        login.setPromptText("Логин");
        password = new PasswordField();
        password.setPromptText("Пароль");
        message = new Label();
        message.setWrapText(true);
        message.setMaxWidth(310);

        GridPane picture = new GridPane();
        picture.setHgap(2);
        picture.setVgap(2);
        for (int i = 0; i < 4; i++) {
            final int slotNumber = i;
            StackPane slot = new StackPane();
            slot.setPrefSize(108, 108);
            slot.setStyle("-fx-border-color: gray;");
            slot.setOnDragOver(event -> {
                if (event.getDragboard().hasString()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            slot.setOnDragDropped(event -> {
                boolean done = false;
                if (event.getDragboard().hasString()) {
                    int source = Integer.parseInt(event.getDragboard().getString());
                    int oldPiece = pieces[source];
                    pieces[source] = pieces[slotNumber];
                    pieces[slotNumber] = oldPiece;
                    refreshPicture();
                    done = true;
                }
                event.setDropCompleted(done);
                event.consume();
            });
            slots[i] = slot;
            picture.add(slot, i % 2, i / 2);
        }

        Button enter = new Button("Войти");
        enter.setDefaultButton(true);
        enter.setOnAction(event -> signIn());

        Label instruction = new Label("Перетащите фрагменты друг на друга, чтобы поменять местами.");
        instruction.setWrapText(true);
        instruction.setMaxWidth(310);

        VBox form = new VBox(8, new Label("Вход в гостиницу"), login, password,
                instruction, picture, enter, message);
        form.setPadding(new Insets(14));
        form.setAlignment(Pos.TOP_CENTER);
        form.setPrefSize(340, 470);
        window.setTitle("Вход");
        window.setResizable(false);
        window.setScene(new Scene(form, 340, 470));
        window.show();
        window.centerOnScreen();
        loadPuzzle();
    }

    /** Загружает перемешанный порядок фрагментов от API. */
    private void loadPuzzle() {
        answer.clear();
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = 0;
            slots[i].getChildren().clear();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(api + "/auth/captcha")).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("API error");
            JsonNode puzzle = json.readTree(response.body());
            puzzleId = puzzle.get("id").asText();
            if (!puzzle.has("pieces")) throw new IllegalStateException("Старая версия API");
            int position = 0;
            for (JsonNode value : puzzle.get("pieces")) {
                pieces[position] = value.asInt();
                position++;
            }
            refreshPicture();
            message.setText("");
        } catch (Exception e) {
            message.setText("Перезапустите API и откройте приложение снова.");
        }
    }

    /** Перерисовывает фрагменты после загрузки или перетаскивания. */
    private void refreshPicture() {
        for (int i = 0; i < pieces.length; i++) {
            final int position = i;
            if (pieces[i] == 0) continue;
            ImageView tile = image(pieces[i], 104);
            tile.setOnDragDetected(event -> {
                var drag = tile.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(String.valueOf(position));
                drag.setContent(content);
                event.consume();
            });
            slots[i].getChildren().setAll(tile);
        }
    }

    /** Загружает картинку фрагмента из ресурсов приложения. */
    private ImageView image(int piece, double size) {
        Image source = new Image(getClass().getResourceAsStream("/captcha/" + piece + ".png"));
        ImageView view = new ImageView(source);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(false);
        return view;
    }

    /** Отправляет пароль и порядок частей серверу для проверки. */
    private void signIn() {
        answer.clear();
        for (int piece : pieces) answer.add(piece);
        message.setText("");
        if (login.getText().isBlank() || password.getText().isBlank()
                || answer.contains(0) || answer.stream().distinct().count() != 4) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("login", login.getText());
        data.put("password", password.getText());
        data.put("puzzleId", puzzleId);
        data.put("answer", answer);
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(api + "/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode result = json.readTree(response.body());
                showWelcome(result.get("login").asText(), result.get("role").asText());
            } else {
                message.setText(errorText(response.body()));
                loadPuzzle();
            }
        } catch (Exception e) {
            message.setText("Не удалось связаться с API.");
        }
    }

    /** Показывает подтверждение входа и роль пользователя. */
    private void showWelcome(String user, String role) {
        Button exit = new Button("Выйти");
        exit.setOnAction(event -> signOut());
        VBox content = new VBox(10, new Label("Вход выполнен: " + user), new Label("Роль: " + role), exit);
        content.setPadding(new Insets(16));
        window.setScene(new Scene(content));
    }

    /** Завершает серверную сессию и возвращает окно входа. */
    private void signOut() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(api + "/auth/logout"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) { }
        showLogin();
    }

    /** Извлекает сообщение об ошибке из ответа API. */
    private String errorText(String body) {
        try {
            JsonNode error = json.readTree(body);
            return error.path("detail").asText(error.path("message").asText("Ошибка входа"));
        } catch (Exception e) {
            return "Ошибка входа";
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
