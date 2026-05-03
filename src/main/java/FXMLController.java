import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FXMLController {
	private static final String INPUT_MODE = env("GUI_INPUT_MODE", "api").toLowerCase(Locale.ROOT);
	private static final String API_BASE = env("GUI_API_BASE", "http://127.0.0.1:8090");
	private static final String DEFAULT_SERIAL_PORT = env("SERIAL_PORT", "COM5");
	private static final int DEFAULT_SERIAL_BAUD = safeInt(env("SERIAL_BAUD", "115200"), 115200);
	private static final int DEFAULT_MODULE_ID = parseDisplayModuleId();
	private static final int HISTORY_LIMIT = 400;
	private static final double MAX_SAFE_OV = 4.25;
	private static final double MIN_SAFE_UV = 2.8;
	private static final List<String> CHART_ORDER = Arrays.asList("voltage", "current", "soc", "status", "cells");
	private static final String[] CELL_SERIES_COLORS = new String[] {
		"#67b6ff", "#f7c453", "#38d889", "#ff8ea0", "#b7a1ff", "#ff6a2a", "#8fd6a3", "#9ad0ff"
	};

	@FXML
	private Label lblConnection;

	@FXML
	private Label lblVoltage;

	@FXML
	private Label lblCurrent;

	@FXML
	private Label lblSoc;

	@FXML
	private Label lblStatus;

	@FXML
	private Label lblLiveStatusLine;

	@FXML
	private ComboBox<String> cmbLiveModule;

	@FXML
	private Button btnRefreshEvents;

	@FXML
	private TableView<EventRow> tblEvents;

	@FXML
	private TableColumn<EventRow, String> colEventTime;

	@FXML
	private TableColumn<EventRow, String> colEventModule;

	@FXML
	private TableColumn<EventRow, String> colEventSeverity;

	@FXML
	private TableColumn<EventRow, String> colEventCode;

	@FXML
	private TableColumn<EventRow, String> colEventMessage;

	@FXML
	private ComboBox<String> cmbStatsModule;

	@FXML
	private ComboBox<String> cmbStatsRange;

	@FXML
	private Button btnRefreshStats;

	@FXML
	private Label lblStatAvgSoc;

	@FXML
	private Label lblStatSocRange;

	@FXML
	private Label lblStatVoltageRange;

	@FXML
	private Label lblStatCurrentRange;

	@FXML
	private Label lblStatPoints;

	@FXML
	private Label lblStatLastStatus;

	@FXML
	private GridPane statsChartsGrid;

	@FXML
	private VBox chartCardVoltage;

	@FXML
	private VBox chartCardCurrent;

	@FXML
	private VBox chartCardSoc;

	@FXML
	private VBox chartCardStatus;

	@FXML
	private VBox chartCardCells;

	@FXML
	private Button btnZoomVoltage;

	@FXML
	private Button btnZoomCurrent;

	@FXML
	private Button btnZoomSoc;

	@FXML
	private Button btnZoomStatus;

	@FXML
	private Button btnZoomCells;

	@FXML
	private LineChart<Number, Number> chartVoltage;

	@FXML
	private LineChart<Number, Number> chartCurrent;

	@FXML
	private LineChart<Number, Number> chartSoc;

	@FXML
	private LineChart<Number, Number> chartStatus;

	@FXML
	private LineChart<Number, Number> chartCells;

	@FXML
	private ComboBox<String> cmbSettingsModule;

	@FXML
	private ComboBox<String> cmbSettingsKey;

	@FXML
	private TextField txtSettingsValue;

	@FXML
	private TextField txtSettingsPort;

	@FXML
	private CheckBox chkSettingsExpertMode;

	@FXML
	private Button btnApplySetting;

	@FXML
	private Button btnApplySafeProfile;

	@FXML
	private Label lblSettingsStatus;

	@FXML
	private TableView<SettingRow> tblSettings;

	@FXML
	private TableColumn<SettingRow, String> colSettingLabel;

	@FXML
	private TableColumn<SettingRow, String> colSettingUnit;

	@FXML
	private TableColumn<SettingRow, String> colSettingMin;

	@FXML
	private TableColumn<SettingRow, String> colSettingMax;

	@FXML
	private TableColumn<SettingRow, String> colSettingM1;

	@FXML
	private TableColumn<SettingRow, String> colSettingM2;

	@FXML
	private TableColumn<SettingRow, String> colSettingM3;

	@FXML
	private TableColumn<SettingRow, String> colSettingM4;

	@FXML
	private TableColumn<SettingRow, String> colSettingWritable;

	@FXML
	private Label lblRpiSummary;

	@FXML
	private TableView<SourceRow> tblRpiSources;

	@FXML
	private TableColumn<SourceRow, String> colSourceAddr;

	@FXML
	private TableColumn<SourceRow, String> colSourceLastSeen;

	@FXML
	private TableColumn<SourceRow, String> colSourceSeconds;

	@FXML
	private TableColumn<SourceRow, String> colSourceOnline;

	@FXML
	private TableColumn<SourceRow, String> colSourceAccepted;

	@FXML
	private TableColumn<SourceRow, String> colSourceLastModule;

	@FXML
	private TableView<ModuleRow> tblRpiModules;

	@FXML
	private TableColumn<ModuleRow, String> colModuleId;

	@FXML
	private TableColumn<ModuleRow, String> colModuleLastSeen;

	@FXML
	private TableColumn<ModuleRow, String> colModuleSeconds;

	@FXML
	private TableColumn<ModuleRow, String> colModuleOnline;

	private final Map<String, VBox> chartCards = new LinkedHashMap<>();
	private final Map<String, Button> chartZoomButtons = new LinkedHashMap<>();
	private final Map<String, LineChart<Number, Number>> chartsByKey = new LinkedHashMap<>();
	private final Map<String, String> settingsDisplayToKey = new LinkedHashMap<>();

	private ScheduledExecutorService poller;
	private volatile boolean refreshRunning;
	private volatile String zoomedChartKey;
	private volatile SettingsSnapshot latestSettingsSnapshot;

	public void initialize() {
		initCombos();
		initTables();
		initCharts();
		wireActions();

		if ("stdin".equals(INPUT_MODE)) {
			lblConnection.setText("Input mode: stdin");
			startStdinReader();
			return;
		}

		lblConnection.setText("Input mode: API " + API_BASE);
		refreshFromApi();
		startApiPolling();
	}

	private void initCombos() {
		if (cmbLiveModule != null) {
			cmbLiveModule.getItems().setAll("1", "2", "3", "4");
			cmbLiveModule.setValue(String.valueOf(DEFAULT_MODULE_ID));
		}
		if (cmbStatsModule != null) {
			cmbStatsModule.getItems().setAll("1", "2", "3", "4");
			cmbStatsModule.setValue(String.valueOf(DEFAULT_MODULE_ID));
		}
		if (cmbStatsRange != null) {
			cmbStatsRange.getItems().setAll("5 min", "15 min", "30 min", "1 h", "3 h", "6 h", "12 h", "24 h");
			cmbStatsRange.setValue("1 h");
		}
		if (cmbSettingsModule != null) {
			cmbSettingsModule.getItems().setAll("1", "2", "3", "4");
			cmbSettingsModule.setValue(String.valueOf(DEFAULT_MODULE_ID));
		}
		if (txtSettingsPort != null) {
			txtSettingsPort.setText(DEFAULT_SERIAL_PORT);
		}
		if (chkSettingsExpertMode != null) {
			chkSettingsExpertMode.setSelected(false);
		}
	}

	private void initTables() {
		if (colEventTime != null) {
			colEventTime.setCellValueFactory(data -> data.getValue().timestampProperty());
			colEventModule.setCellValueFactory(data -> data.getValue().moduleProperty());
			colEventSeverity.setCellValueFactory(data -> data.getValue().severityProperty());
			colEventCode.setCellValueFactory(data -> data.getValue().codeProperty());
			colEventMessage.setCellValueFactory(data -> data.getValue().messageProperty());
		}

		if (colSourceAddr != null) {
			colSourceAddr.setCellValueFactory(data -> data.getValue().sourceProperty());
			colSourceLastSeen.setCellValueFactory(data -> data.getValue().lastSeenProperty());
			colSourceSeconds.setCellValueFactory(data -> data.getValue().secondsProperty());
			colSourceOnline.setCellValueFactory(data -> data.getValue().onlineProperty());
			colSourceAccepted.setCellValueFactory(data -> data.getValue().acceptedProperty());
			colSourceLastModule.setCellValueFactory(data -> data.getValue().lastModuleProperty());
		}

		if (colModuleId != null) {
			colModuleId.setCellValueFactory(data -> data.getValue().moduleProperty());
			colModuleLastSeen.setCellValueFactory(data -> data.getValue().lastSeenProperty());
			colModuleSeconds.setCellValueFactory(data -> data.getValue().secondsProperty());
			colModuleOnline.setCellValueFactory(data -> data.getValue().onlineProperty());
		}

		if (colSettingLabel != null) {
			colSettingLabel.setCellValueFactory(data -> data.getValue().labelProperty());
			colSettingUnit.setCellValueFactory(data -> data.getValue().unitProperty());
			colSettingMin.setCellValueFactory(data -> data.getValue().minProperty());
			colSettingMax.setCellValueFactory(data -> data.getValue().maxProperty());
			colSettingM1.setCellValueFactory(data -> data.getValue().module1Property());
			colSettingM2.setCellValueFactory(data -> data.getValue().module2Property());
			colSettingM3.setCellValueFactory(data -> data.getValue().module3Property());
			colSettingM4.setCellValueFactory(data -> data.getValue().module4Property());
			colSettingWritable.setCellValueFactory(data -> data.getValue().writableProperty());
		}
	}

	private void initCharts() {
		registerChart("voltage", chartCardVoltage, btnZoomVoltage, chartVoltage);
		registerChart("current", chartCardCurrent, btnZoomCurrent, chartCurrent);
		registerChart("soc", chartCardSoc, btnZoomSoc, chartSoc);
		registerChart("status", chartCardStatus, btnZoomStatus, chartStatus);
		registerChart("cells", chartCardCells, btnZoomCells, chartCells);
		renderChartLayout();
	}

	private void registerChart(String key, VBox card, Button zoomButton, LineChart<Number, Number> chart) {
		if (card != null) {
			chartCards.put(key, card);
		}
		if (zoomButton != null) {
			chartZoomButtons.put(key, zoomButton);
			zoomButton.setOnAction(event -> toggleChartZoom(key));
		}
		if (chart != null) {
			chartsByKey.put(key, chart);
			chart.setAnimated(false);
			chart.setCreateSymbols(false);
			chart.setLegendVisible("cells".equals(key));
			chart.setPrefHeight(260.0);
			chart.setMinHeight(220.0);
			if (chart.getXAxis() instanceof NumberAxis) {
				NumberAxis axis = (NumberAxis) chart.getXAxis();
				axis.setForceZeroInRange(false);
				axis.setTickLabelFill(Color.web("#d7e2ef"));
			}
			if (chart.getYAxis() instanceof NumberAxis) {
				NumberAxis axis = (NumberAxis) chart.getYAxis();
				axis.setForceZeroInRange(false);
				axis.setTickLabelFill(Color.web("#d7e2ef"));
			}
		}
	}

	private void toggleChartZoom(String key) {
		if (key == null || key.isBlank()) {
			return;
		}
		if (key.equals(zoomedChartKey)) {
			zoomedChartKey = null;
		} else {
			zoomedChartKey = key;
		}
		renderChartLayout();
	}

	private void renderChartLayout() {
		if (statsChartsGrid == null) {
			return;
		}

		statsChartsGrid.getChildren().clear();
		for (String key : CHART_ORDER) {
			setChartZoomState(key, false);
		}

		if (zoomedChartKey != null && chartCards.containsKey(zoomedChartKey)) {
			VBox zoomed = chartCards.get(zoomedChartKey);
			addCardToGrid(zoomed, 0, 0, 2);
			setChartZoomState(zoomedChartKey, true);

			int idx = 0;
			for (String key : CHART_ORDER) {
				if (key.equals(zoomedChartKey) || !chartCards.containsKey(key)) {
					continue;
				}
				int row = 1 + (idx / 2);
				int col = idx % 2;
				addCardToGrid(chartCards.get(key), row, col, 1);
				idx++;
			}
			Platform.runLater(this::applyChartStyling);
			return;
		}

		int idx = 0;
		for (String key : CHART_ORDER) {
			if (!chartCards.containsKey(key)) {
				continue;
			}
			int row = idx / 2;
			int col = idx % 2;
			int span = "cells".equals(key) ? 2 : 1;
			if ("cells".equals(key) && col != 0) {
				idx++;
				row = idx / 2;
				col = 0;
			}
			addCardToGrid(chartCards.get(key), row, col, span);
			idx += span;
		}
		Platform.runLater(this::applyChartStyling);
	}

	private void addCardToGrid(VBox card, int row, int col, int colSpan) {
		if (card == null) {
			return;
		}
		GridPane.setRowIndex(card, row);
		GridPane.setColumnIndex(card, col);
		GridPane.setColumnSpan(card, colSpan);
		GridPane.setHgrow(card, Priority.ALWAYS);
		card.setMaxWidth(Double.MAX_VALUE);
		statsChartsGrid.getChildren().add(card);
	}

	private void setChartZoomState(String key, boolean zoomed) {
		Button button = chartZoomButtons.get(key);
		if (button != null) {
			button.setText(zoomed ? "Shrink" : "Enlarge");
		}
		VBox card = chartCards.get(key);
		if (card != null) {
			card.setPrefHeight(zoomed ? 640.0 : 320.0);
		}
		LineChart<Number, Number> chart = chartsByKey.get(key);
		if (chart != null) {
			chart.setPrefHeight(zoomed ? 540.0 : 260.0);
			chart.setMinHeight(zoomed ? 520.0 : 220.0);
		}
	}

	private void applyChartStyling() {
		styleChart(chartVoltage, "#ff6a2a", true);
		styleChart(chartCurrent, "#f7c453", true);
		styleChart(chartSoc, "#38d889", true);
		styleChart(chartStatus, "#ff8ea0", true);
		styleChart(chartCells, "#67b6ff", false);
	}

	private void styleChart(LineChart<Number, Number> chart, String color, boolean hideLegend) {
		if (chart == null) {
			return;
		}
		chart.setLegendVisible(!hideLegend);

		Node plotBackground = chart.lookup(".chart-plot-background");
		if (plotBackground != null) {
			plotBackground.setStyle("-fx-background-color: #162233;");
		}

		if (!hideLegend) {
			for (Node line : chart.lookupAll(".chart-series-line")) {
				int colorIndex = resolveSeriesColorIndex(line.getStyleClass());
				String seriesColor = CELL_SERIES_COLORS[colorIndex % CELL_SERIES_COLORS.length];
				line.setStyle("-fx-stroke: " + seriesColor + "; -fx-stroke-width: 2.4px;");
			}
			for (Node symbol : chart.lookupAll(".chart-line-symbol")) {
				symbol.setStyle("-fx-background-color: transparent, transparent;");
			}
			for (Node legendSymbol : chart.lookupAll(".chart-legend-item-symbol")) {
				int colorIndex = resolveSeriesColorIndex(legendSymbol.getStyleClass());
				String seriesColor = CELL_SERIES_COLORS[colorIndex % CELL_SERIES_COLORS.length];
				legendSymbol.setStyle("-fx-background-color: " + seriesColor + ", " + seriesColor + ";");
			}
			return;
		}

		for (Node line : chart.lookupAll(".chart-series-line")) {
			line.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2.8px;");
		}
	}

	private int resolveSeriesColorIndex(List<String> styleClasses) {
		if (styleClasses == null) {
			return 0;
		}
		for (String styleClass : styleClasses) {
			if (styleClass == null || !styleClass.startsWith("default-color")) {
				continue;
			}
			String number = styleClass.substring("default-color".length());
			if (isInteger(number)) {
				int parsed = safeInt(number, 0);
				return Math.max(parsed, 0);
			}
		}
		return 0;
	}

	private void wireActions() {
		if (cmbLiveModule != null) {
			cmbLiveModule.setOnAction(event -> triggerManualRefresh());
		}
		if (cmbStatsModule != null) {
			cmbStatsModule.setOnAction(event -> triggerManualRefresh());
		}
		if (cmbStatsRange != null) {
			cmbStatsRange.setOnAction(event -> triggerManualRefresh());
		}
		if (btnRefreshEvents != null) {
			btnRefreshEvents.setOnAction(event -> triggerManualRefresh());
		}
		if (btnRefreshStats != null) {
			btnRefreshStats.setOnAction(event -> triggerManualRefresh());
		}
		if (btnApplySetting != null) {
			btnApplySetting.setOnAction(event -> submitSettingUpdate());
		}
		if (btnApplySafeProfile != null) {
			btnApplySafeProfile.setOnAction(event -> submitSafeProfile());
		}
		if (chkSettingsExpertMode != null) {
			chkSettingsExpertMode.setOnAction(event -> updateSettingsModeStatus());
		}
	}

	private void triggerManualRefresh() {
		if ("stdin".equals(INPUT_MODE)) {
			return;
		}
		Thread refreshThread = new Thread(this::refreshFromApi, "gui-manual-refresh");
		refreshThread.setDaemon(true);
		refreshThread.start();
	}

	private void startApiPolling() {
		poller = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread thread = new Thread(r, "gui-api-poller");
			thread.setDaemon(true);
			return thread;
		});
		poller.scheduleAtFixedRate(this::refreshFromApi, 0, 1, TimeUnit.SECONDS);
	}

	private void startStdinReader() {
		Thread readThread = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
				String line;
				while ((line = in.readLine()) != null) {
					String data = line.trim();
					if (!data.isEmpty()) {
						Platform.runLater(() -> parseAndUpdateLiveFromLine(data));
					}
				}
			} catch (IOException ex) {
				Platform.runLater(() -> lblLiveStatusLine.setText("Input stream error: " + ex.getMessage()));
			}
		}, "gui-stdin-reader");
		readThread.setDaemon(true);
		readThread.start();
	}

	private void refreshFromApi() {
		if (refreshRunning) {
			return;
		}
		refreshRunning = true;
		try {
			int liveModule = getSelectedModule(cmbLiveModule);
			int statsModule = getSelectedModule(cmbStatsModule);
			int sinceMinutes = parseRangeMinutes();

			LiveSnapshot live = parseLiveSnapshot(httpGet("/api/latest?moduleId=" + liveModule), liveModule);
			StatsSnapshot stats = parseStatsSnapshot(httpGet("/api/statistics?moduleId=" + statsModule + "&sinceMinutes=" + sinceMinutes));
			List<HistoryPoint> history = parseHistory(httpGet("/api/history?moduleId=" + statsModule + "&sinceMinutes=" + sinceMinutes + "&limit=" + HISTORY_LIMIT));
			List<EventRow> events = parseEvents(httpGet("/api/events?limit=40"));
			RpiSnapshot rpi = parseRpiSnapshot(httpGet("/api/rpi-status"));
			SettingsSnapshot settings = parseSettingsSnapshot(httpGet("/api/cell-settings"));

			Platform.runLater(() -> {
				applyLive(live, liveModule);
				applyStats(stats, history, sinceMinutes);
				tblEvents.setItems(FXCollections.observableArrayList(events));
				applyRpi(rpi);
				latestSettingsSnapshot = settings;
				applySettings(settings);
				lblConnection.setText("API OK | " + API_BASE + " | " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
			});
		} catch (Exception ex) {
			String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			Platform.runLater(() -> lblConnection.setText("API error: " + msg));
		} finally {
			refreshRunning = false;
		}
	}

	private void submitSettingUpdate() {
		if ("stdin".equals(INPUT_MODE)) {
			setSettingsStatus("Settings update is unavailable in stdin mode.", false);
			return;
		}

		String moduleValue = cmbSettingsModule == null ? null : cmbSettingsModule.getValue();
		String settingDisplay = cmbSettingsKey == null ? null : cmbSettingsKey.getValue();
		String numericValue = txtSettingsValue == null ? "" : txtSettingsValue.getText().trim();
		String serialPort = txtSettingsPort == null ? DEFAULT_SERIAL_PORT : txtSettingsPort.getText().trim();
		boolean expertMode = chkSettingsExpertMode != null && chkSettingsExpertMode.isSelected();

		int moduleId = safeInt(moduleValue, 0);
		String settingKey = settingsDisplayToKey.get(settingDisplay);
		if (moduleId < 1 || moduleId > 4) {
			setSettingsStatus("Select module 1..4.", false);
			return;
		}
		if (settingKey == null || settingKey.isBlank()) {
			setSettingsStatus("No writable setting selected.", false);
			return;
		}
		if (numericValue.isBlank()) {
			setSettingsStatus("Value is required.", false);
			return;
		}
		if (!isNumeric(numericValue)) {
			setSettingsStatus("Value must be numeric.", false);
			return;
		}
		if (serialPort.isBlank()) {
			setSettingsStatus("Serial port is required.", false);
			return;
		}

		double numeric = safeDouble(numericValue, Double.NaN);
		String validationError = validateSettingBeforeWrite(moduleId, settingKey, numeric, expertMode);
		if (validationError != null) {
			setSettingsStatus(validationError, false);
			return;
		}
		if (!confirmSettingsWrite(
			"Write setting to TinyBMS?",
			"Module " + moduleId + ", key " + settingKey + ", value " + formatDecimal(numeric, 4) + ", port " + serialPort
		)) {
			setSettingsStatus("Update canceled.", false);
			return;
		}

		setSettingsStatus("Writing to TinyBMS over UART...", true);
		Thread thread = new Thread(() -> {
			try (TinyBmsUartSettingsService uart = new TinyBmsUartSettingsService(serialPort, DEFAULT_SERIAL_BAUD)) {
				uart.writeSetting(settingKey, numeric);
				String body = "moduleId=" + urlEncode(String.valueOf(moduleId))
					+ "&key=" + urlEncode(settingKey)
					+ "&value=" + urlEncode(numericValue);
				httpPostForm("/api/cell-settings", body);
				Platform.runLater(() -> setSettingsStatus("UART write OK and backend synced.", true));
				refreshFromApi();
			} catch (Exception ex) {
				Platform.runLater(() -> setSettingsStatus("Update failed: " + ex.getMessage(), false));
			}
		}, "gui-setting-update");
		thread.setDaemon(true);
		thread.start();
	}

	private void submitSafeProfile() {
		if ("stdin".equals(INPUT_MODE)) {
			setSettingsStatus("SAFE profile is unavailable in stdin mode.", false);
			return;
		}

		String moduleValue = cmbSettingsModule == null ? null : cmbSettingsModule.getValue();
		String serialPort = txtSettingsPort == null ? DEFAULT_SERIAL_PORT : txtSettingsPort.getText().trim();
		int moduleId = safeInt(moduleValue, 0);
		if (moduleId < 1 || moduleId > 4) {
			setSettingsStatus("Select module 1..4.", false);
			return;
		}
		if (serialPort.isBlank()) {
			setSettingsStatus("Serial port is required.", false);
			return;
		}
		if (!confirmSettingsWrite(
			"Apply SAFE profile?",
			"Module " + moduleId + ", OV=4.2 V, UV=3.0 V, port " + serialPort
		)) {
			setSettingsStatus("SAFE profile canceled.", false);
			return;
		}

		setSettingsStatus("Applying SAFE profile over UART...", true);
		Thread thread = new Thread(() -> {
			try (TinyBmsUartSettingsService uart = new TinyBmsUartSettingsService(serialPort, DEFAULT_SERIAL_BAUD)) {
				uart.applySafeLiIonProfile();
				httpPostForm("/api/cell-settings", "moduleId=" + urlEncode(String.valueOf(moduleId)) + "&key=overvoltage_protection_v&value=4.2");
				httpPostForm("/api/cell-settings", "moduleId=" + urlEncode(String.valueOf(moduleId)) + "&key=undervoltage_protection_v&value=3.0");
				Platform.runLater(() -> setSettingsStatus("SAFE profile applied and backend synced.", true));
				refreshFromApi();
			} catch (Exception ex) {
				Platform.runLater(() -> setSettingsStatus("SAFE profile failed: " + ex.getMessage(), false));
			}
		}, "gui-safe-profile-update");
		thread.setDaemon(true);
		thread.start();
	}

	private void setSettingsStatus(String message, boolean ok) {
		if (lblSettingsStatus == null) {
			return;
		}
		lblSettingsStatus.setText(message);
		lblSettingsStatus.setStyle(ok ? "-fx-text-fill: #29d391;" : "-fx-text-fill: #ff6b6b;");
	}

	private void updateSettingsModeStatus() {
		boolean expertMode = chkSettingsExpertMode != null && chkSettingsExpertMode.isSelected();
		setSettingsStatus(
			expertMode
				? "EXPERT mode: wider ranges and direct UART write for supported TinyBMS keys."
				: "SAFE mode: extra checks enabled. OV <= 4.25 V and UV >= 2.8 V.",
			true
		);
	}

	private String validateSettingBeforeWrite(int moduleId, String settingKey, double value, boolean expertMode) {
		if (!Double.isFinite(value)) {
			return "Value must be numeric.";
		}
		if (!TinyBmsUartSettingsService.supportsKey(settingKey)) {
			return "Selected setting is not supported for TinyBMS UART write.";
		}

		SettingsSnapshot snapshot = latestSettingsSnapshot;
		if (snapshot != null) {
			SettingBounds bounds = snapshot.boundsByKey.get(settingKey);
			if (bounds != null && (value < bounds.min || value > bounds.max)) {
				return "Value out of range. Allowed: " + formatDecimal(bounds.min, 4) + " .. " + formatDecimal(bounds.max, 4);
			}
		}

		double currentOv = getModuleSettingValue(moduleId, "overvoltage_protection_v");
		double currentUv = getModuleSettingValue(moduleId, "undervoltage_protection_v");
		double targetOv = "overvoltage_protection_v".equals(settingKey) ? value : currentOv;
		double targetUv = "undervoltage_protection_v".equals(settingKey) ? value : currentUv;

		if (Double.isFinite(targetOv) && Double.isFinite(targetUv) && targetOv <= targetUv) {
			return "OV must be higher than UV.";
		}
		if (!expertMode) {
			if ("overvoltage_protection_v".equals(settingKey) && value > MAX_SAFE_OV) {
				return "SAFE mode blocks OV above " + MAX_SAFE_OV + " V.";
			}
			if ("undervoltage_protection_v".equals(settingKey) && value < MIN_SAFE_UV) {
				return "SAFE mode blocks UV below " + MIN_SAFE_UV + " V.";
			}
		}
		return null;
	}

	private double getModuleSettingValue(int moduleId, String key) {
		SettingsSnapshot snapshot = latestSettingsSnapshot;
		if (snapshot == null) {
			return Double.NaN;
		}
		Map<String, Double> moduleValues = snapshot.valuesByModule.getOrDefault(moduleId, Collections.emptyMap());
		return moduleValues.getOrDefault(key, Double.NaN);
	}

	private boolean confirmSettingsWrite(String title, String content) {
		Alert alert = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.OK, ButtonType.CANCEL);
		alert.setTitle(title);
		alert.setHeaderText(title);
		return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
	}

	private void parseAndUpdateLiveFromLine(String line) {
		if (!line.startsWith("BMS")) {
			lblLiveStatusLine.setText("stdin: " + line);
			return;
		}

		String[] parts = line.split(",");
		if (parts.length < 5) {
			lblLiveStatusLine.setText("Invalid BMS frame");
			return;
		}

		int moduleId = 1;
		int baseIndex = 1;
		if (parts.length >= 6 && isInteger(parts[1])) {
			int candidate = safeInt(parts[1], 1);
			if (candidate >= 1 && candidate <= 4) {
				moduleId = candidate;
				baseIndex = 2;
			}
		}

		if (moduleId != getSelectedModule(cmbLiveModule) || parts.length < baseIndex + 4) {
			return;
		}

		double voltage = safeDouble(parts[baseIndex], 0.0);
		double current = safeDouble(parts[baseIndex + 1], 0.0);
		double socRaw = safeDouble(parts[baseIndex + 2], 0.0);
		int statusCode = safeInt(parts[baseIndex + 3], 0);
		double socPercent = socRaw > 1000.0 ? socRaw / 1_000_000.0 : socRaw;

		lblVoltage.setText(String.format(Locale.US, "%.3f V", voltage));
		lblCurrent.setText(String.format(Locale.US, "%.3f A", current));
		lblSoc.setText(String.format(Locale.US, "%.2f %%", socPercent));
		lblStatus.setText(statusCode + " (" + describeStatusCode(statusCode) + ")");
		lblLiveStatusLine.setText("Module " + moduleId + " | stdin | " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
	}

	private int getSelectedModule(ComboBox<String> combo) {
		if (combo == null || combo.getValue() == null) {
			return DEFAULT_MODULE_ID;
		}
		return safeInt(combo.getValue(), DEFAULT_MODULE_ID);
	}

	private int parseRangeMinutes() {
		if (cmbStatsRange == null || cmbStatsRange.getValue() == null) {
			return 60;
		}
		String text = cmbStatsRange.getValue().trim().toLowerCase(Locale.ROOT);
		if (text.endsWith("h")) {
			return safeInt(text.replace("h", "").trim(), 1) * 60;
		}
		return safeInt(text.replace("min", "").trim(), 60);
	}

	private String httpGet(String endpoint) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + endpoint).openConnection();
		connection.setRequestMethod("GET");
		connection.setConnectTimeout(2000);
		connection.setReadTimeout(2000);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		} finally {
			connection.disconnect();
		}
	}

	private String httpPostForm(String endpoint, String body) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + endpoint).openConnection();
		connection.setRequestMethod("POST");
		connection.setConnectTimeout(2500);
		connection.setReadTimeout(2500);
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		try (OutputStream output = connection.getOutputStream()) {
			output.write(bytes);
		}

		int status = connection.getResponseCode();
		BufferedReader reader = null;
		try {
			if (status >= 200 && status < 300) {
				reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
			} else {
				reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8));
			}
			StringBuilder sb = new StringBuilder();
			String line;
			while (reader != null && (line = reader.readLine()) != null) {
				sb.append(line);
			}
			String response = sb.toString();
			if (status >= 200 && status < 300) {
				return response;
			}
			String err = extractStringField(response, "error", "HTTP " + status);
			throw new IOException(err);
		} finally {
			if (reader != null) {
				reader.close();
			}
			connection.disconnect();
		}
	}

	private LiveSnapshot parseLiveSnapshot(String json, int moduleId) {
		List<String> objects = extractObjectsFromArray(json);
		if (objects.isEmpty()) {
			return null;
		}
		String first = objects.get(0);
		LiveSnapshot snapshot = new LiveSnapshot();
		snapshot.moduleId = moduleId;
		snapshot.voltage = extractNumberField(first, "voltageV", 0.0);
		snapshot.current = extractNumberField(first, "currentA", 0.0);
		snapshot.soc = extractNumberField(first, "socPercent", 0.0);
		snapshot.statusCode = (int) extractNumberField(first, "statusCode", 0.0);
		snapshot.timestamp = extractStringField(first, "timestamp", "");
		return snapshot;
	}

	private StatsSnapshot parseStatsSnapshot(String json) {
		List<String> objects = extractObjectsFromArray(json);
		if (objects.isEmpty()) {
			return null;
		}
		String obj = objects.get(0);
		StatsSnapshot snapshot = new StatsSnapshot();
		snapshot.avgSoc = extractNumberField(obj, "avgSoc", 0.0);
		snapshot.minSoc = extractNumberField(obj, "minSoc", 0.0);
		snapshot.maxSoc = extractNumberField(obj, "maxSoc", 0.0);
		snapshot.minVoltage = extractNumberField(obj, "minVoltage", 0.0);
		snapshot.maxVoltage = extractNumberField(obj, "maxVoltage", 0.0);
		snapshot.minCurrent = extractNumberField(obj, "minCurrent", 0.0);
		snapshot.maxCurrent = extractNumberField(obj, "maxCurrent", 0.0);
		snapshot.points = (int) extractNumberField(obj, "sampleCount", 0.0);
		snapshot.lastStatusCode = (int) extractNumberField(obj, "lastStatusCode", 0.0);
		return snapshot;
	}

	private List<HistoryPoint> parseHistory(String json) {
		List<String> objects = extractObjectsFromArray(json);
		List<HistoryPoint> result = new ArrayList<>();
		for (String obj : objects) {
			HistoryPoint point = new HistoryPoint();
			point.voltage = extractNumberField(obj, "voltageV", 0.0);
			point.current = extractNumberField(obj, "currentA", 0.0);
			point.soc = extractNumberField(obj, "socPercent", 0.0);
			point.statusCode = extractNumberField(obj, "statusCode", 0.0);
			point.cells = extractNumberArrayField(obj, "cellsMv");
			result.add(point);
		}
		Collections.reverse(result);
		return result;
	}

	private List<EventRow> parseEvents(String json) {
		List<String> objects = extractObjectsFromArray(json);
		List<EventRow> rows = new ArrayList<>();
		for (String obj : objects) {
			int moduleId = (int) extractNumberField(obj, "moduleId", 0.0);
			rows.add(new EventRow(
				extractStringField(obj, "timestamp", "-"),
				moduleId <= 0 ? "SYSTEM" : String.valueOf(moduleId),
				extractStringField(obj, "severity", "-"),
				String.valueOf((int) extractNumberField(obj, "eventCode", 0.0)),
				extractStringField(obj, "message", "-")
			));
		}
		return rows;
	}

	private RpiSnapshot parseRpiSnapshot(String json) {
		RpiSnapshot snapshot = new RpiSnapshot();
		snapshot.overallOnline = extractBoolField(json, "overallOnline", false);
		snapshot.offlineThreshold = (int) extractNumberField(json, "offlineThresholdSec", 0.0);

		for (String obj : extractObjectsFromArray(extractArrayField(json, "sources"))) {
			snapshot.sources.add(new SourceRow(
				extractStringField(obj, "source", "-"),
				extractStringField(obj, "lastSeen", "-"),
				formatSeconds(extractNumberField(obj, "secondsSinceSeen", -1.0)),
				extractBoolField(obj, "online", false) ? "ONLINE" : "OFFLINE",
				String.valueOf((long) extractNumberField(obj, "acceptedCount", 0.0)),
				formatNullableInt(extractNumberField(obj, "lastModuleId", -1.0))
			));
		}

		for (String obj : extractObjectsFromArray(extractArrayField(json, "modules"))) {
			snapshot.modules.add(new ModuleRow(
				String.valueOf((int) extractNumberField(obj, "moduleId", 0.0)),
				extractStringField(obj, "lastSeen", "-"),
				formatSeconds(extractNumberField(obj, "secondsSinceSeen", -1.0)),
				extractBoolField(obj, "online", false) ? "ONLINE" : "OFFLINE"
			));
		}
		return snapshot;
	}

	private SettingsSnapshot parseSettingsSnapshot(String json) {
		SettingsSnapshot snapshot = new SettingsSnapshot();
		List<String> moduleObjects = extractObjectsFromArray(json);
		Map<String, SettingAccumulator> byKey = new LinkedHashMap<>();

		for (String moduleObj : moduleObjects) {
			int moduleId = (int) extractNumberField(moduleObj, "moduleId", 0.0);
			if (moduleId < 1 || moduleId > 4) {
				continue;
			}

			List<String> settings = extractObjectsFromArray(extractArrayField(moduleObj, "settings"));
			for (String settingObj : settings) {
				String key = extractStringField(settingObj, "key", "");
				if (key.isBlank()) {
					continue;
				}

				SettingAccumulator acc = byKey.computeIfAbsent(key, ignored -> new SettingAccumulator());
				acc.key = key;
				acc.label = extractStringField(settingObj, "label", key);
				acc.unit = extractStringField(settingObj, "unit", "");
				acc.min = extractNumberField(settingObj, "min", 0.0);
				acc.max = extractNumberField(settingObj, "max", 0.0);
				acc.writable = extractBoolField(settingObj, "writable", false);
				double parsedValue = extractNumberField(settingObj, "value", Double.NaN);
				acc.values[moduleId] = parsedValue;
				snapshot.valuesByModule.computeIfAbsent(moduleId, ignored -> new HashMap<>()).put(key, parsedValue);
			}
		}

		for (SettingAccumulator acc : byKey.values()) {
			snapshot.rows.add(new SettingRow(
				acc.label,
				acc.unit,
				formatDecimal(acc.min, 4),
				formatDecimal(acc.max, 4),
				formatSettingValue(acc.values[1]),
				formatSettingValue(acc.values[2]),
				formatSettingValue(acc.values[3]),
				formatSettingValue(acc.values[4]),
				acc.writable ? "YES" : "RO"
			));
			snapshot.boundsByKey.put(acc.key, new SettingBounds(acc.min, acc.max));

			if (acc.writable && TinyBmsUartSettingsService.supportsKey(acc.key)) {
				snapshot.writableOptions.add(new SettingOption(acc.key, acc.label + " (" + acc.unit + ")"));
			}
		}
		return snapshot;
	}

	private void applyLive(LiveSnapshot snapshot, int moduleId) {
		if (snapshot == null) {
			lblLiveStatusLine.setText("Module " + moduleId + " | waiting for API data");
			return;
		}
		lblVoltage.setText(String.format(Locale.US, "%.3f V", snapshot.voltage));
		lblCurrent.setText(String.format(Locale.US, "%.3f A", snapshot.current));
		lblSoc.setText(String.format(Locale.US, "%.2f %%", snapshot.soc));
		lblStatus.setText(snapshot.statusCode + " (" + describeStatusCode(snapshot.statusCode) + ")");
		lblLiveStatusLine.setText("Module " + moduleId + " | ts=" + emptyToDash(snapshot.timestamp));
	}

	private void applyStats(StatsSnapshot stats, List<HistoryPoint> history, int sinceMinutes) {
		if (stats == null) {
			lblStatAvgSoc.setText("-");
			lblStatSocRange.setText("-");
			lblStatVoltageRange.setText("-");
			lblStatCurrentRange.setText("-");
			lblStatPoints.setText("0");
			lblStatLastStatus.setText("-");
			clearAllCharts();
			return;
		}

		lblStatAvgSoc.setText(String.format(Locale.US, "%.2f %%", stats.avgSoc));
		lblStatSocRange.setText(String.format(Locale.US, "%.2f - %.2f %%", stats.minSoc, stats.maxSoc));
		lblStatVoltageRange.setText(String.format(Locale.US, "%.3f - %.3f V", stats.minVoltage, stats.maxVoltage));
		lblStatCurrentRange.setText(String.format(Locale.US, "%.3f - %.3f A", stats.minCurrent, stats.maxCurrent));
		lblStatPoints.setText(String.valueOf(stats.points));
		lblStatLastStatus.setText(stats.lastStatusCode + " (" + describeStatusCode(stats.lastStatusCode) + ")");

		XYChart.Series<Number, Number> voltageSeries = new XYChart.Series<>();
		voltageSeries.setName("Voltage");
		XYChart.Series<Number, Number> currentSeries = new XYChart.Series<>();
		currentSeries.setName("Current");
		XYChart.Series<Number, Number> socSeries = new XYChart.Series<>();
		socSeries.setName("SOC %");
		XYChart.Series<Number, Number> statusSeries = new XYChart.Series<>();
		statusSeries.setName("Status");

		for (int i = 0; i < history.size(); i++) {
			HistoryPoint point = history.get(i);
			int x = i + 1;
			voltageSeries.getData().add(new XYChart.Data<>(x, point.voltage));
			currentSeries.getData().add(new XYChart.Data<>(x, point.current));
			socSeries.getData().add(new XYChart.Data<>(x, point.soc));
			statusSeries.getData().add(new XYChart.Data<>(x, point.statusCode));
		}

		if (chartVoltage != null) {
			chartVoltage.setTitle("Voltage (last " + sinceMinutes + " min)");
			chartVoltage.getData().setAll(voltageSeries);
		}
		if (chartCurrent != null) {
			chartCurrent.setTitle("Current (last " + sinceMinutes + " min)");
			chartCurrent.getData().setAll(currentSeries);
		}
		if (chartSoc != null) {
			chartSoc.setTitle("SOC (last " + sinceMinutes + " min)");
			chartSoc.getData().setAll(socSeries);
		}
		if (chartStatus != null) {
			chartStatus.setTitle("Status (last " + sinceMinutes + " min)");
			chartStatus.getData().setAll(statusSeries);
		}
		applyCellChart(history, sinceMinutes);
		Platform.runLater(this::applyChartStyling);
	}

	private void clearAllCharts() {
		for (LineChart<Number, Number> chart : chartsByKey.values()) {
			if (chart != null) {
				chart.getData().clear();
			}
		}
	}

	private void applyCellChart(List<HistoryPoint> history, int sinceMinutes) {
		if (chartCells == null) {
			return;
		}

		int maxCellCount = 0;
		for (HistoryPoint point : history) {
			maxCellCount = Math.max(maxCellCount, point.cells.size());
		}
		if (maxCellCount == 0) {
			chartCells.setTitle("Cell Voltages (no cell data)");
			chartCells.getData().clear();
			return;
		}

		List<XYChart.Series<Number, Number>> seriesList = new ArrayList<>();
		for (int cellIndex = 0; cellIndex < maxCellCount; cellIndex++) {
			XYChart.Series<Number, Number> cellSeries = new XYChart.Series<>();
			cellSeries.setName("Cell " + (cellIndex + 1));
			boolean hasData = false;
			for (int i = 0; i < history.size(); i++) {
				HistoryPoint point = history.get(i);
				if (cellIndex >= point.cells.size()) {
					continue;
				}
				double value = point.cells.get(cellIndex);
				if (!Double.isFinite(value)) {
					continue;
				}
				hasData = true;
				cellSeries.getData().add(new XYChart.Data<>(i + 1, value));
			}
			if (hasData) {
				seriesList.add(cellSeries);
			}
		}

		chartCells.setTitle("Cell Voltages (last " + sinceMinutes + " min)");
		chartCells.getData().setAll(seriesList);
	}

	private void applySettings(SettingsSnapshot snapshot) {
		if (snapshot == null) {
			tblSettings.setItems(FXCollections.observableArrayList());
			if (cmbSettingsKey != null) {
				cmbSettingsKey.getItems().clear();
			}
			settingsDisplayToKey.clear();
			updateSettingsModeStatus();
			return;
		}

		tblSettings.setItems(FXCollections.observableArrayList(snapshot.rows));

		if (cmbSettingsKey == null) {
			return;
		}

		String previous = cmbSettingsKey.getValue();
		settingsDisplayToKey.clear();
		List<String> displayValues = new ArrayList<>();
		for (SettingOption option : snapshot.writableOptions) {
			displayValues.add(option.display);
			settingsDisplayToKey.put(option.display, option.key);
		}
		cmbSettingsKey.getItems().setAll(displayValues);
		if (previous != null && settingsDisplayToKey.containsKey(previous)) {
			cmbSettingsKey.setValue(previous);
		} else if (!displayValues.isEmpty()) {
			cmbSettingsKey.setValue(displayValues.get(0));
		}
		updateSettingsModeStatus();
	}

	private void applyRpi(RpiSnapshot snapshot) {
		if (snapshot == null) {
			lblRpiSummary.setText("No RPi status data.");
			tblRpiSources.setItems(FXCollections.observableArrayList());
			tblRpiModules.setItems(FXCollections.observableArrayList());
			return;
		}

		lblRpiSummary.setText(
			"Overall: " + (snapshot.overallOnline ? "ONLINE" : "OFFLINE") +
			" | Offline threshold: " + snapshot.offlineThreshold + "s"
		);
		tblRpiSources.setItems(FXCollections.observableArrayList(snapshot.sources));
		tblRpiModules.setItems(FXCollections.observableArrayList(snapshot.modules));
	}

	private static String extractArrayField(String json, String key) {
		int keyPos = json.indexOf("\"" + key + "\"");
		if (keyPos < 0) {
			return "[]";
		}
		int arrayStart = json.indexOf('[', keyPos);
		if (arrayStart < 0) {
			return "[]";
		}
		int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');
		if (arrayEnd < 0) {
			return "[]";
		}
		return json.substring(arrayStart, arrayEnd + 1);
	}

	private static List<String> extractObjectsFromArray(String jsonArray) {
		if (jsonArray == null || jsonArray.length() < 2) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>();
		boolean inString = false;
		int depth = 0;
		int start = -1;
		for (int i = 0; i < jsonArray.length(); i++) {
			char ch = jsonArray.charAt(i);
			char prev = i > 0 ? jsonArray.charAt(i - 1) : 0;
			if (ch == '"' && prev != '\\') {
				inString = !inString;
			}
			if (inString) {
				continue;
			}
			if (ch == '{') {
				if (depth == 0) {
					start = i;
				}
				depth++;
			} else if (ch == '}') {
				depth--;
				if (depth == 0 && start >= 0) {
					result.add(jsonArray.substring(start, i + 1));
					start = -1;
				}
			}
		}
		return result;
	}

	private static List<Double> extractNumberArrayField(String objectJson, String key) {
		String array = extractArrayField(objectJson, key);
		if (array.length() < 2) {
			return Collections.emptyList();
		}
		String body = array.substring(1, array.length() - 1).trim();
		if (body.isEmpty()) {
			return Collections.emptyList();
		}

		String[] parts = body.split(",");
		List<Double> values = new ArrayList<>();
		for (String part : parts) {
			String trimmed = part.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			values.add(safeDouble(trimmed, Double.NaN));
		}
		return values;
	}

	private static int findMatchingBracket(String text, int startIndex, char open, char close) {
		boolean inString = false;
		int depth = 0;
		for (int i = startIndex; i < text.length(); i++) {
			char ch = text.charAt(i);
			char prev = i > 0 ? text.charAt(i - 1) : 0;
			if (ch == '"' && prev != '\\') {
				inString = !inString;
			}
			if (inString) {
				continue;
			}
			if (ch == open) {
				depth++;
			} else if (ch == close) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String extractStringField(String objectJson, String key, String fallback) {
		Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
		Matcher m = p.matcher(objectJson);
		if (!m.find()) {
			return fallback;
		}
		return jsonUnescape(m.group(1));
	}

	private static double extractNumberField(String objectJson, String key, double fallback) {
		Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
		Matcher m = p.matcher(objectJson);
		if (!m.find()) {
			return fallback;
		}
		return safeDouble(m.group(1), fallback);
	}

	private static boolean extractBoolField(String objectJson, String key, boolean fallback) {
		Pattern p = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(true|false)");
		Matcher m = p.matcher(objectJson);
		if (!m.find()) {
			return fallback;
		}
		return Boolean.parseBoolean(m.group(1));
	}

	private static String jsonUnescape(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replace("\\\"", "\"")
			.replace("\\n", "\n")
			.replace("\\r", "\r")
			.replace("\\\\", "\\");
	}

	private static String formatSeconds(double secondsValue) {
		int seconds = (int) Math.round(secondsValue);
		return seconds < 0 ? "-" : String.valueOf(seconds);
	}

	private static String formatNullableInt(double value) {
		int number = (int) Math.round(value);
		return number < 0 ? "-" : String.valueOf(number);
	}

	private static String emptyToDash(String value) {
		return value == null || value.trim().isEmpty() ? "-" : value;
	}

	private static String describeStatusCode(int statusCode) {
		if (statusCode == 155) {
			return "Simulator nominal";
		}
		if (statusCode == 144) {
			return "Device reported";
		}
		return "Unknown";
	}

	private static int parseDisplayModuleId() {
		int value = safeInt(System.getenv("GUI_MODULE_ID"), 1);
		return value < 1 || value > 4 ? 1 : value;
	}

	private static String env(String key, String fallback) {
		String value = System.getenv(key);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	private static boolean isInteger(String value) {
		if (value == null || value.trim().isEmpty()) {
			return false;
		}
		try {
			Integer.parseInt(value.trim());
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static boolean isNumeric(String value) {
		if (value == null || value.trim().isEmpty()) {
			return false;
		}
		try {
			Double.parseDouble(value.trim());
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static int safeInt(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static double safeDouble(String value, double fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static String formatDecimal(double value, int precision) {
		if (!Double.isFinite(value)) {
			return "-";
		}
		return String.format(Locale.US, "%." + precision + "f", value);
	}

	private static String formatSettingValue(double value) {
		if (!Double.isFinite(value)) {
			return "-";
		}
		return String.format(Locale.US, "%.3f", value);
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static final class LiveSnapshot {
		int moduleId;
		double voltage;
		double current;
		double soc;
		int statusCode;
		String timestamp;
	}

	private static final class StatsSnapshot {
		double avgSoc;
		double minSoc;
		double maxSoc;
		double minVoltage;
		double maxVoltage;
		double minCurrent;
		double maxCurrent;
		int points;
		int lastStatusCode;
	}

	private static final class HistoryPoint {
		double voltage;
		double current;
		double soc;
		double statusCode;
		List<Double> cells = Collections.emptyList();
	}

	private static final class RpiSnapshot {
		boolean overallOnline;
		int offlineThreshold;
		final List<SourceRow> sources = new ArrayList<>();
		final List<ModuleRow> modules = new ArrayList<>();
	}

	private static final class SettingsSnapshot {
		final List<SettingRow> rows = new ArrayList<>();
		final List<SettingOption> writableOptions = new ArrayList<>();
		final Map<Integer, Map<String, Double>> valuesByModule = new HashMap<>();
		final Map<String, SettingBounds> boundsByKey = new HashMap<>();
	}

	private static final class SettingOption {
		final String key;
		final String display;

		SettingOption(String key, String display) {
			this.key = key;
			this.display = display;
		}
	}

	private static final class SettingAccumulator {
		String key;
		String label;
		String unit;
		double min;
		double max;
		boolean writable;
		final double[] values = new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
	}

	private static final class SettingBounds {
		final double min;
		final double max;

		SettingBounds(double min, double max) {
			this.min = min;
			this.max = max;
		}
	}

	public static final class EventRow {
		private final SimpleStringProperty timestamp;
		private final SimpleStringProperty module;
		private final SimpleStringProperty severity;
		private final SimpleStringProperty code;
		private final SimpleStringProperty message;

		EventRow(String timestamp, String module, String severity, String code, String message) {
			this.timestamp = new SimpleStringProperty(timestamp);
			this.module = new SimpleStringProperty(module);
			this.severity = new SimpleStringProperty(severity);
			this.code = new SimpleStringProperty(code);
			this.message = new SimpleStringProperty(message);
		}

		public SimpleStringProperty timestampProperty() { return timestamp; }
		public SimpleStringProperty moduleProperty() { return module; }
		public SimpleStringProperty severityProperty() { return severity; }
		public SimpleStringProperty codeProperty() { return code; }
		public SimpleStringProperty messageProperty() { return message; }
	}

	public static final class SourceRow {
		private final SimpleStringProperty source;
		private final SimpleStringProperty lastSeen;
		private final SimpleStringProperty seconds;
		private final SimpleStringProperty online;
		private final SimpleStringProperty accepted;
		private final SimpleStringProperty lastModule;

		SourceRow(String source, String lastSeen, String seconds, String online, String accepted, String lastModule) {
			this.source = new SimpleStringProperty(source);
			this.lastSeen = new SimpleStringProperty(lastSeen);
			this.seconds = new SimpleStringProperty(seconds);
			this.online = new SimpleStringProperty(online);
			this.accepted = new SimpleStringProperty(accepted);
			this.lastModule = new SimpleStringProperty(lastModule);
		}

		public SimpleStringProperty sourceProperty() { return source; }
		public SimpleStringProperty lastSeenProperty() { return lastSeen; }
		public SimpleStringProperty secondsProperty() { return seconds; }
		public SimpleStringProperty onlineProperty() { return online; }
		public SimpleStringProperty acceptedProperty() { return accepted; }
		public SimpleStringProperty lastModuleProperty() { return lastModule; }
	}

	public static final class ModuleRow {
		private final SimpleStringProperty module;
		private final SimpleStringProperty lastSeen;
		private final SimpleStringProperty seconds;
		private final SimpleStringProperty online;

		ModuleRow(String module, String lastSeen, String seconds, String online) {
			this.module = new SimpleStringProperty(module);
			this.lastSeen = new SimpleStringProperty(lastSeen);
			this.seconds = new SimpleStringProperty(seconds);
			this.online = new SimpleStringProperty(online);
		}

		public SimpleStringProperty moduleProperty() { return module; }
		public SimpleStringProperty lastSeenProperty() { return lastSeen; }
		public SimpleStringProperty secondsProperty() { return seconds; }
		public SimpleStringProperty onlineProperty() { return online; }
	}

	public static final class SettingRow {
		private final SimpleStringProperty label;
		private final SimpleStringProperty unit;
		private final SimpleStringProperty min;
		private final SimpleStringProperty max;
		private final SimpleStringProperty module1;
		private final SimpleStringProperty module2;
		private final SimpleStringProperty module3;
		private final SimpleStringProperty module4;
		private final SimpleStringProperty writable;

		SettingRow(String label, String unit, String min, String max, String module1, String module2, String module3, String module4, String writable) {
			this.label = new SimpleStringProperty(label);
			this.unit = new SimpleStringProperty(unit);
			this.min = new SimpleStringProperty(min);
			this.max = new SimpleStringProperty(max);
			this.module1 = new SimpleStringProperty(module1);
			this.module2 = new SimpleStringProperty(module2);
			this.module3 = new SimpleStringProperty(module3);
			this.module4 = new SimpleStringProperty(module4);
			this.writable = new SimpleStringProperty(writable);
		}

		public SimpleStringProperty labelProperty() { return label; }
		public SimpleStringProperty unitProperty() { return unit; }
		public SimpleStringProperty minProperty() { return min; }
		public SimpleStringProperty maxProperty() { return max; }
		public SimpleStringProperty module1Property() { return module1; }
		public SimpleStringProperty module2Property() { return module2; }
		public SimpleStringProperty module3Property() { return module3; }
		public SimpleStringProperty module4Property() { return module4; }
		public SimpleStringProperty writableProperty() { return writable; }
	}
}
