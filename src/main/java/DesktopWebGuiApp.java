import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.net.URI;

public class DesktopWebGuiApp extends Application {
    private static final String DEFAULT_WEB_GUI_URL = env("DESKTOP_WEB_GUI_URL",
        "http://127.0.0.1:" + env("WEB_UI_PORT", "8088") + "/dashboard.html");

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        TextField addressField = new TextField(DEFAULT_WEB_GUI_URL);
        addressField.setPrefColumnCount(42);
        HBox.setHgrow(addressField, Priority.ALWAYS);

        Label statusLabel = new Label("Loading desktop Web GUI...");
        statusLabel.setTextFill(Color.web("#d7e2ef"));

        Button homeButton = buildButton("Home");
        Button reloadButton = buildButton("Reload");
        Button browserButton = buildButton("Open in Browser");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(54, 54);
        spinner.setVisible(true);

        Label emptyTitle = new Label("BMS Desktop Web GUI");
        emptyTitle.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label emptyText = new Label(
            "Ta aplikacja desktopowa pokazuje ten sam interfejs co Web GUI.\n" +
            "Najpierw uruchom backend i Web UI, np. komendą run_server_stack.bat."
        );
        emptyText.setStyle("-fx-text-fill: #d7e2ef; -fx-font-size: 13px;");
        emptyText.setWrapText(true);
        emptyText.setMaxWidth(520);

        VBox placeholder = new VBox(12, emptyTitle, emptyText, spinner);
        placeholder.setAlignment(Pos.CENTER);
        placeholder.setPadding(new Insets(24));
        placeholder.setStyle("-fx-background-color: linear-gradient(to bottom, #1f2a37, #162233);");
        placeholder.setMouseTransparent(true);

        StackPane centerPane = new StackPane(webView, placeholder);
        centerPane.setStyle("-fx-background-color: #162233;");

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1f2a37;");
        root.setCenter(centerPane);
        root.setTop(buildToolbar(homeButton, reloadButton, browserButton, addressField));
        root.setBottom(buildStatusBar(statusLabel));

        Scene scene = new Scene(root, 1360, 860);
        stage.setTitle("BMS Desktop Viewer");
        stage.setScene(scene);
        stage.setWidth(1360);
        stage.setHeight(860);
        stage.setMinWidth(980);
        stage.setMinHeight(700);
        stage.setMaximized(false);
        stage.setFullScreen(false);
        stage.centerOnScreen();
        stage.show();

        Runnable loadRequestedUrl = () -> {
            String requested = addressField.getText() == null ? "" : addressField.getText().trim();
            if (requested.isEmpty()) {
                addressField.setText(DEFAULT_WEB_GUI_URL);
                requested = DEFAULT_WEB_GUI_URL;
            }
            engine.load(requested);
        };

        addressField.setOnAction(event -> loadRequestedUrl.run());
        homeButton.setOnAction(event -> {
            addressField.setText(DEFAULT_WEB_GUI_URL);
            loadRequestedUrl.run();
        });
        reloadButton.setOnAction(event -> engine.reload());
        browserButton.setOnAction(event -> openInSystemBrowser(addressField.getText(), statusLabel));
        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                stage.setFullScreen(false);
                stage.setMaximized(false);
            }
        });

        engine.locationProperty().addListener((ObservableValue<? extends String> obs, String oldValue, String newValue) -> {
            if (newValue != null && !newValue.isBlank()) {
                addressField.setText(newValue);
            }
        });

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            boolean loading = newState == Worker.State.SCHEDULED || newState == Worker.State.RUNNING;
            spinner.setVisible(loading);
            placeholder.setVisible(loading || newState == Worker.State.FAILED);
            if (newState == Worker.State.SUCCEEDED) {
                statusLabel.setText("Connected: " + engine.getLocation());
                placeholder.setVisible(false);
            } else if (newState == Worker.State.RUNNING) {
                statusLabel.setText("Loading: " + addressField.getText());
            } else if (newState == Worker.State.FAILED) {
                Throwable error = engine.getLoadWorker().getException();
                statusLabel.setText("Load error: " + (error == null ? "unknown error" : error.getMessage()));
                emptyText.setText(
                    "Nie moge otworzyc Web GUI pod adresem:\n" + addressField.getText() +
                    "\n\nNajpierw uruchom:\nrun_server_stack.bat"
                );
            } else if (newState == Worker.State.CANCELLED) {
                statusLabel.setText("Loading cancelled.");
            }
        });

        stage.setOnCloseRequest(event -> Platform.exit());
        loadRequestedUrl.run();
    }

    private static HBox buildToolbar(Button homeButton, Button reloadButton, Button browserButton, TextField addressField) {
        Label title = new Label("Desktop Web GUI");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: white;");

        HBox toolbar = new HBox(10, title, homeButton, reloadButton, browserButton, addressField);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(14, 16, 12, 16));
        toolbar.setStyle("-fx-background-color: linear-gradient(to right, #14202d, #223448);");
        return toolbar;
    }

    private static HBox buildStatusBar(Label statusLabel) {
        HBox statusBar = new HBox(statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(8, 16, 10, 16));
        statusBar.setStyle("-fx-background-color: #162233;");
        return statusBar;
    }

    private static Button buildButton(String text) {
        Button button = new Button(text);
        button.setStyle(
            "-fx-background-color: #ff6a2a;" +
            "-fx-text-fill: white;" +
            "-fx-font-weight: bold;" +
            "-fx-background-radius: 8;" +
            "-fx-padding: 8 14 8 14;"
        );
        return button;
    }

    private static void openInSystemBrowser(String url, Label statusLabel) {
        try {
            if (url == null || url.trim().isEmpty()) {
                statusLabel.setText("Browser open failed: empty URL");
                return;
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                statusLabel.setText("Browser open failed: Desktop browse not supported");
                return;
            }
            Desktop.getDesktop().browse(URI.create(url.trim()));
            statusLabel.setText("Opened in browser: " + url.trim());
        } catch (Exception ex) {
            statusLabel.setText("Browser open failed: " + ex.getMessage());
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
