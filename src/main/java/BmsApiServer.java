import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.fazecast.jSerialComm.SerialPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BmsApiServer {
	private static final int DEFAULT_PORT = 8090;
	private static final int MAX_HISTORY = 5000;
	private static final int MAX_EVENTS = 2000;
	private static final int EVENT_DB_DISCONNECTED = 9001;
	private static final int EVENT_DATA_SOURCE_DISCONNECTED = 9002;
	private static final int EVENT_BMS_DISCONNECTED = 9003;
	private static final int MAX_UART_LOG_LINES = 60;
	private static final int MIN_REAL_CELL_MV = 500;
	private static final int MAX_REAL_CELL_MV = 5000;

	private static final Object LOCK = new Object();
	private static final Map<Integer, BmsReading> latestByModule = new ConcurrentHashMap<>();
	private static final Deque<BmsReading> history = new ArrayDeque<>();
	private static final Deque<BmsEvent> events = new ArrayDeque<>();
	private static final Set<Integer> allowedModules = new HashSet<>();
	private static final Set<Integer> modulesSeen = ConcurrentHashMap.newKeySet();
	private static final Map<Integer, Map<String, Double>> cellSettingsByModule = new ConcurrentHashMap<>();
	private static final Map<String, IngestSourceState> ingestSources = new ConcurrentHashMap<>();
	private static final Deque<String> uartLogs = new ArrayDeque<>();

	private static final SettingDefinition[] SETTING_DEFINITIONS = new SettingDefinition[] {
		new SettingDefinition("fully_charged_voltage_v", "Fully Charged Voltage", "V", 3.4, 4.25, 4.2, true),
		new SettingDefinition("overvoltage_protection_v", "Overvoltage Protection", "V", 3.65, 4.35, 4.25, true),
		new SettingDefinition("undervoltage_protection_v", "Undervoltage Protection", "V", 2.50, 3.10, 2.80, true),
		new SettingDefinition("charge_overcurrent_a", "Max Charge Current", "A", 1.0, 60.0, 30.0, true),
		new SettingDefinition("discharge_overcurrent_a", "Max Discharge Current", "A", 1.0, 150.0, 80.0, true),
		new SettingDefinition("early_balancing_threshold_v", "Balancing Start Voltage", "V", 3.30, 4.10, 3.70, true),
		new SettingDefinition("discharge_temperature_high_c", "Overtemperature Cutoff", "C", 45.0, 75.0, 60.0, true),
		new SettingDefinition("series_cells", "Number of Series Cells", "count", 1.0, 32.0, 16.0, false)
	};

	private static volatile Connection dbConnection;
	private static volatile ScheduledExecutorService retentionExecutor;
	private static volatile boolean dbWasConnected;
	private static volatile Process uartProcess;
	private static volatile String uartProcessPort = "";
	private static volatile Instant uartStartedAt;

	public static void main(String[] args) throws IOException {
		int port = parseIntEnv("BMS_API_PORT", DEFAULT_PORT);
		int retentionDays = parseIntEnv("BMS_DB_RETENTION_DAYS", 7);
		int retentionIntervalMinutes = parseIntEnv("BMS_DB_RETENTION_INTERVAL_MIN", 60);
		allowedModules.addAll(parseAllowedModules(System.getenv("BMS_ALLOWED_MODULES")));
		initSettingsDefaults();
		initDatabase();
		startRetentionTask(retentionDays, retentionIntervalMinutes);
		loadSettingsFromDb();
		persistAllSettingsIfMissing();

		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/api/health", new HealthHandler());
		server.createContext("/api/ingest", new IngestHandler());
		server.createContext("/api/latest", new LatestHandler());
		server.createContext("/api/history", new HistoryHandler());
		server.createContext("/api/events", new EventHandler());
		server.createContext("/api/statistics", new StatisticsHandler());
		server.createContext("/api/bms-connection", new BmsConnectionHandler());
		server.createContext("/api/rpi-status", new BmsConnectionHandler());
		server.createContext("/api/cell-settings", new CellSettingsHandler());
		server.createContext("/api/runtime-config", new RuntimeConfigHandler());
		server.createContext("/api/uart-control", new UartControlHandler());
		server.createContext("/api/bms-control", new BmsControlHandler());
		server.createContext("/api/bms-events", new BmsEventsReadHandler());
		server.setExecutor(null);

		System.out.println("[BmsApiServer] Listening on port " + port);
		if (dbConnection == null) {
			System.out.println("[BmsApiServer] Database disabled or unavailable. Running in memory-only mode.");
		}
		if (!allowedModules.isEmpty()) {
			System.out.println("[BmsApiServer] Allowed modules: " + allowedModules);
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			stopUartProcess();
			shutdownRetentionTask();
		}));
		server.start();
	}

	private static void initDatabase() {
		String jdbcUrl = resolveDbUrl();
		String dbUser = resolveDbUser();
		String dbPass = resolveDbPass();

		try {
			dbConnection = DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
			try (Statement statement = dbConnection.createStatement()) {
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_readings (" +
						"id BIGINT PRIMARY KEY AUTO_INCREMENT," +
						"created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
						"module_id TINYINT NOT NULL," +
						"voltage_v DECIMAL(10,3) NOT NULL," +
						"current_a DECIMAL(10,3) NOT NULL," +
						"soc_percent DECIMAL(7,3) NOT NULL," +
						"status_code INT NOT NULL," +
						"raw_line TEXT NOT NULL" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_cell_readings (" +
						"id BIGINT PRIMARY KEY AUTO_INCREMENT," +
						"reading_id BIGINT NOT NULL," +
						"module_id TINYINT NOT NULL," +
						"cell_index INT NOT NULL," +
						"cell_mv INT NOT NULL," +
						"FOREIGN KEY (reading_id) REFERENCES bms_readings(id) ON DELETE CASCADE" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_events (" +
						"id BIGINT PRIMARY KEY AUTO_INCREMENT," +
						"created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
						"module_id TINYINT NOT NULL," +
						"event_code INT NOT NULL," +
						"severity VARCHAR(16) NOT NULL," +
						"message TEXT NOT NULL," +
						"raw_line TEXT NOT NULL" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_cell_settings (" +
						"module_id TINYINT NOT NULL," +
						"key_name VARCHAR(128) NOT NULL," +
						"label_name VARCHAR(128) NOT NULL," +
						"unit_name VARCHAR(32) NOT NULL," +
						"min_value DOUBLE NOT NULL," +
						"max_value DOUBLE NOT NULL," +
						"value_num DOUBLE NOT NULL," +
						"writable BOOLEAN NOT NULL," +
						"updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
						"PRIMARY KEY (module_id, key_name)" +
					")"
				);
				statement.executeUpdate(
					"CREATE TABLE IF NOT EXISTS bms_ingest_sources (" +
						"source_addr VARCHAR(128) PRIMARY KEY," +
						"last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
						"accepted_count BIGINT NOT NULL DEFAULT 0," +
						"last_payload TEXT NULL," +
						"last_module_id TINYINT NULL" +
					")"
				);
			}
			dbWasConnected = true;
			System.out.println("[BmsApiServer] Database initialized.");
		} catch (Exception ex) {
			dbConnection = null;
			System.err.println("[BmsApiServer] DB init failed: " + ex.getMessage());
		}
	}

	private static void startRetentionTask(int retentionDays, int retentionIntervalMinutes) {
		if (dbConnection == null) {
			return;
		}
		if (retentionDays <= 0) {
			System.out.println("[BmsApiServer] DB retention disabled (BMS_DB_RETENTION_DAYS <= 0).");
			return;
		}
		if (retentionIntervalMinutes < 1) {
			retentionIntervalMinutes = 60;
		}

		purgeOldData(retentionDays);

		final int retentionDaysFinal = retentionDays;
		retentionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "bms-db-retention");
			thread.setDaemon(true);
			return thread;
		});
		retentionExecutor.scheduleAtFixedRate(
			() -> purgeOldData(retentionDaysFinal),
			retentionIntervalMinutes,
			retentionIntervalMinutes,
			TimeUnit.MINUTES
		);
		System.out.println(
			"[BmsApiServer] DB retention enabled: keep " + retentionDays +
			" day(s), cleanup every " + retentionIntervalMinutes + " minute(s)."
		);
	}

	private static void purgeOldData(int retentionDays) {
		if (dbConnection == null || retentionDays <= 0) {
			return;
		}

		String cutoffExpression = "NOW() - INTERVAL " + retentionDays + " DAY";
		String purgeEventsSql = "DELETE FROM bms_events WHERE created_at < " + cutoffExpression;
		String purgeReadingsSql = "DELETE FROM bms_readings WHERE created_at < " + cutoffExpression;

		try (Statement statement = dbConnection.createStatement()) {
			int deletedEvents = statement.executeUpdate(purgeEventsSql);
			int deletedReadings = statement.executeUpdate(purgeReadingsSql);
			if (deletedEvents > 0 || deletedReadings > 0) {
				System.out.println(
					"[BmsApiServer] Retention purge: deleted readings=" + deletedReadings +
					", events=" + deletedEvents
				);
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Retention purge failed: " + ex.getMessage());
		}
	}

	private static void shutdownRetentionTask() {
		ScheduledExecutorService executor = retentionExecutor;
		if (executor == null) {
			return;
		}
		executor.shutdownNow();
	}

	private static void initSettingsDefaults() {
		for (int moduleId = 1; moduleId <= 4; moduleId++) {
			Map<String, Double> moduleMap = new ConcurrentHashMap<>();
			for (SettingDefinition definition : SETTING_DEFINITIONS) {
				moduleMap.put(definition.key, definition.defaultValue);
			}
			cellSettingsByModule.put(moduleId, moduleMap);
		}
	}

	private static void loadSettingsFromDb() {
		if (dbConnection == null) {
			return;
		}

		String sql = "SELECT module_id, key_name, value_num FROM bms_cell_settings";
		try (Statement stmt = dbConnection.createStatement();
			 java.sql.ResultSet rs = stmt.executeQuery(sql)) {
			while (rs.next()) {
				int moduleId = rs.getInt("module_id");
				String key = rs.getString("key_name");
				double value = rs.getDouble("value_num");
				if (moduleId >= 1 && moduleId <= 4 && getSettingDefinition(key) != null) {
					cellSettingsByModule.computeIfAbsent(moduleId, x -> new ConcurrentHashMap<>()).put(key, value);
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed loading settings from DB: " + ex.getMessage());
		}
	}

	private static void persistAllSettingsIfMissing() {
		if (dbConnection == null) {
			return;
		}
		for (int moduleId = 1; moduleId <= 4; moduleId++) {
			for (SettingDefinition definition : SETTING_DEFINITIONS) {
				double value = cellSettingsByModule.get(moduleId).get(definition.key);
				persistSetting(moduleId, definition, value);
			}
		}
	}

	private static void persistSetting(int moduleId, SettingDefinition definition, double value) {
		if (dbConnection == null) {
			return;
		}

		String sql =
			"INSERT INTO bms_cell_settings (module_id, key_name, label_name, unit_name, min_value, max_value, value_num, writable) " +
			"VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
			"ON DUPLICATE KEY UPDATE value_num = VALUES(value_num), label_name = VALUES(label_name), unit_name = VALUES(unit_name), " +
			"min_value = VALUES(min_value), max_value = VALUES(max_value), writable = VALUES(writable)";

		try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
			stmt.setInt(1, moduleId);
			stmt.setString(2, definition.key);
			stmt.setString(3, definition.label);
			stmt.setString(4, definition.unit);
			stmt.setDouble(5, definition.minValue);
			stmt.setDouble(6, definition.maxValue);
			stmt.setDouble(7, value);
			stmt.setBoolean(8, definition.writable);
			stmt.executeUpdate();
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed persisting setting: " + ex.getMessage());
		}
	}

	private static IngestResult ingestLine(String line) {
		String trimmed = line == null ? "" : line.trim();
		if (trimmed.isEmpty()) {
			return IngestResult.rejected();
		}

		if (trimmed.startsWith("BMS")) {
			BmsReading reading = parseBms(trimmed);
			if (reading == null) {
				return IngestResult.rejected();
			}
			if (!allowedModules.isEmpty() && !allowedModules.contains(reading.moduleId)) {
				return IngestResult.rejected();
			}
			modulesSeen.add(reading.moduleId);
			updateDetectedSeriesCells(reading);

			synchronized (LOCK) {
				latestByModule.put(reading.moduleId, reading);
				history.addFirst(reading);
				while (history.size() > MAX_HISTORY) {
					history.removeLast();
				}
			}
			persistReading(reading);
			return IngestResult.accepted(reading.moduleId, false, trimmed);
		}

		if (trimmed.startsWith("EVENT")) {
			BmsEvent event = parseEvent(trimmed);
			if (event == null) {
				return IngestResult.rejected();
			}
			if (!allowedModules.isEmpty() && !allowedModules.contains(event.moduleId)) {
				return IngestResult.rejected();
			}

			synchronized (LOCK) {
				events.addFirst(event);
				while (events.size() > MAX_EVENTS) {
					events.removeLast();
				}
			}
			persistEvent(event);
			return IngestResult.accepted(event.moduleId, false, trimmed);
		}

		if (trimmed.startsWith("HEARTBEAT")) {
			int moduleId = parseHeartbeatModuleId(trimmed);
			if (moduleId > 0 && !allowedModules.isEmpty() && !allowedModules.contains(moduleId)) {
				return IngestResult.rejected();
			}
			return IngestResult.accepted(moduleId, true, trimmed);
		}

		return IngestResult.rejected();
	}

	private static int parseHeartbeatModuleId(String line) {
		String[] parts = line.split(",");
		if (parts.length < 2 || !isInteger(parts[1])) {
			return 0;
		}
		int moduleId = safeInt(parts[1], 0);
		return moduleId >= 1 && moduleId <= 4 ? moduleId : 0;
	}

	private static void persistIngestSource(String sourceAddr, int acceptedCount, int heartbeatCount, String lastPayload, int lastModuleId) {
		if (sourceAddr == null || sourceAddr.isBlank() || (acceptedCount <= 0 && heartbeatCount <= 0)) {
			return;
		}

		updateIngestSourceMemory(sourceAddr, acceptedCount, lastPayload, lastModuleId);

		if (dbConnection == null) {
			return;
		}

		String sql =
			"INSERT INTO bms_ingest_sources (source_addr, accepted_count, last_payload, last_module_id) VALUES (?, ?, ?, ?) " +
			"ON DUPLICATE KEY UPDATE last_seen = CURRENT_TIMESTAMP, accepted_count = accepted_count + VALUES(accepted_count), " +
			"last_payload = CASE WHEN VALUES(last_payload) = '' THEN last_payload ELSE VALUES(last_payload) END, " +
			"last_module_id = CASE WHEN VALUES(last_module_id) IS NULL THEN last_module_id ELSE VALUES(last_module_id) END";

		try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
			stmt.setString(1, sourceAddr);
			stmt.setInt(2, acceptedCount);
			stmt.setString(3, lastPayload == null ? "" : lastPayload);
			if (lastModuleId > 0) {
				stmt.setInt(4, lastModuleId);
			} else {
				stmt.setNull(4, java.sql.Types.TINYINT);
			}
			stmt.executeUpdate();
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed to persist ingest source: " + ex.getMessage());
		}
	}

	private static void updateIngestSourceMemory(String sourceAddr, int acceptedCount, String lastPayload, int lastModuleId) {
		ingestSources.compute(sourceAddr, (key, current) -> {
			IngestSourceState state = current == null ? new IngestSourceState() : current;
			state.lastSeen = Instant.now();
			if (acceptedCount > 0) {
				state.acceptedCount += acceptedCount;
			}
			if (lastModuleId > 0) {
				state.lastModuleId = lastModuleId;
			}
			if (lastPayload != null && !lastPayload.isBlank()) {
				state.lastPayload = lastPayload;
			}
			return state;
		});
	}

	private static BmsReading parseBms(String line) {
		String[] parts = line.split(",");
		if (parts.length < 5) {
			return null;
		}

		int moduleId = 1;
		int index = 1;
		if (parts.length >= 6 && isInteger(parts[1])) {
			int parsedModule = safeInt(parts[1], 1);
			if (parsedModule >= 1 && parsedModule <= 4) {
				moduleId = parsedModule;
				index = 2;
			}
		}

		if (parts.length < index + 4) {
			return null;
		}

		double voltage = normalizeVoltage(safeDouble(parts[index], 0.0), parts.length - (index + 4));
		double current = normalizeCurrent(safeDouble(parts[index + 1], 0.0));
		double socRaw = safeDouble(parts[index + 2], 0.0);
		int statusCode = safeInt(parts[index + 3], 0);
		double socPercent = socRaw > 1000.0 ? socRaw / 1_000_000.0 : socRaw;

		List<Integer> cells = new ArrayList<>();
		for (int i = index + 4; i < parts.length; i++) {
			if (isInteger(parts[i])) {
				int cellMv = normalizeCellMv(safeInt(parts[i], 0));
				if (cellMv > 0) {
					cells.add(cellMv);
				}
			}
		}
		voltage = reconcilePackVoltageWithCells(voltage, cells);

		BmsReading reading = new BmsReading();
		reading.timestamp = Instant.now().toString();
		reading.moduleId = moduleId;
		reading.voltageV = voltage;
		reading.currentA = current;
		reading.socPercent = socPercent;
		reading.statusCode = statusCode;
		reading.cellMv = cells;
		reading.rawLine = line;
		return reading;
	}

	private static double normalizeVoltage(double rawVoltage, int detectedCellFields) {
		if (!Double.isFinite(rawVoltage)) {
			return 0.0;
		}
		if (rawVoltage > 1000.0) {
			return rawVoltage / 1000.0;
		}
		if (rawVoltage > 100.0 && detectedCellFields > 0) {
			return rawVoltage / 10.0;
		}
		return rawVoltage;
	}

	private static double reconcilePackVoltageWithCells(double packVoltage, List<Integer> cells) {
		if (cells == null || cells.isEmpty()) {
			return packVoltage;
		}
		int sumMv = 0;
		for (Integer cellMv : cells) {
			if (cellMv != null && cellMv > 0) {
				sumMv += cellMv;
			}
		}
		if (sumMv <= 0) {
			return packVoltage;
		}
		double cellsVoltage = sumMv / 1000.0;
		if (!Double.isFinite(packVoltage) || packVoltage <= 0.0) {
			return cellsVoltage;
		}
		return Math.abs(cellsVoltage - packVoltage) >= 0.20 ? cellsVoltage : packVoltage;
	}

	private static double normalizeCurrent(double rawCurrent) {
		if (!Double.isFinite(rawCurrent)) {
			return 0.0;
		}
		double amps = Math.abs(rawCurrent) > 200.0 ? rawCurrent / 1000.0 : rawCurrent;
		double deadband = parseDoubleEnv("BMS_CURRENT_ZERO_DEADBAND_A", 0.50);
		return Math.abs(amps) < deadband ? 0.0 : amps;
	}

	private static int normalizeCellMv(int rawCellMv) {
		int cellMv = rawCellMv >= 10000 ? (int) Math.round(rawCellMv / 10.0) : rawCellMv;
		if (cellMv < MIN_REAL_CELL_MV || cellMv > MAX_REAL_CELL_MV) {
			return -1;
		}
		return cellMv;
	}

	private static void updateDetectedSeriesCells(BmsReading reading) {
		if (reading == null || reading.cellMv == null || reading.cellMv.isEmpty()) {
			return;
		}
		SettingDefinition definition = getSettingDefinition("series_cells");
		if (definition == null) {
			return;
		}
		double value = reading.cellMv.size();
		cellSettingsByModule.computeIfAbsent(reading.moduleId, ignored -> new ConcurrentHashMap<>()).put(definition.key, value);
		persistSetting(reading.moduleId, definition, value);
	}

	private static BmsEvent parseEvent(String line) {
		String[] parts = line.split(",");
		if (parts.length < 2) {
			return null;
		}

		int moduleId = 1;
		int index = 1;
		if (parts.length >= 3 && isInteger(parts[1])) {
			int parsedModule = safeInt(parts[1], 1);
			if (parsedModule >= 1 && parsedModule <= 4) {
				moduleId = parsedModule;
				index = 2;
			}
		}

		int eventCode = 0;
		if (index < parts.length && isInteger(parts[index])) {
			eventCode = safeInt(parts[index], 0);
			index++;
		}

		String severity = "INFO";
		if (index < parts.length) {
			String candidate = parts[index].trim().toUpperCase(Locale.ROOT);
			if ("INFO".equals(candidate) || "WARN".equals(candidate) || "ERROR".equals(candidate)) {
				severity = candidate;
				index++;
			}
		}

		StringBuilder messageBuilder = new StringBuilder();
		for (int i = index; i < parts.length; i++) {
			if (messageBuilder.length() > 0) {
				messageBuilder.append(',');
			}
			messageBuilder.append(parts[i]);
		}
		String message = messageBuilder.length() == 0 ? line : messageBuilder.toString().trim();

		BmsEvent event = new BmsEvent();
		event.timestamp = Instant.now().toString();
		event.moduleId = moduleId;
		event.eventCode = eventCode;
		event.severity = severity;
		event.message = message;
		event.rawLine = line;
		return event;
	}

	private static void persistReading(BmsReading reading) {
		if (dbConnection == null) {
			return;
		}

		String insertReading =
			"INSERT INTO bms_readings (module_id, voltage_v, current_a, soc_percent, status_code, raw_line) " +
			"VALUES (?, ?, ?, ?, ?, ?)";
		String insertCell =
			"INSERT INTO bms_cell_readings (reading_id, module_id, cell_index, cell_mv) VALUES (?, ?, ?, ?)";

		try (PreparedStatement stmtReading = dbConnection.prepareStatement(insertReading, Statement.RETURN_GENERATED_KEYS)) {
			stmtReading.setInt(1, reading.moduleId);
			stmtReading.setDouble(2, reading.voltageV);
			stmtReading.setDouble(3, reading.currentA);
			stmtReading.setDouble(4, reading.socPercent);
			stmtReading.setInt(5, reading.statusCode);
			stmtReading.setString(6, reading.rawLine);
			stmtReading.executeUpdate();

			long readingId = -1;
			try (java.sql.ResultSet keys = stmtReading.getGeneratedKeys()) {
				if (keys.next()) {
					readingId = keys.getLong(1);
				}
			}

			if (readingId > 0 && !reading.cellMv.isEmpty()) {
				try (PreparedStatement stmtCell = dbConnection.prepareStatement(insertCell)) {
					for (int i = 0; i < reading.cellMv.size(); i++) {
						stmtCell.setLong(1, readingId);
						stmtCell.setInt(2, reading.moduleId);
						stmtCell.setInt(3, i + 1);
						stmtCell.setInt(4, reading.cellMv.get(i));
						stmtCell.addBatch();
					}
					stmtCell.executeBatch();
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed to persist reading: " + ex.getMessage());
		}
	}

	private static void persistEvent(BmsEvent event) {
		if (dbConnection == null) {
			return;
		}

		String insert =
			"INSERT INTO bms_events (module_id, event_code, severity, message, raw_line) VALUES (?, ?, ?, ?, ?)";

		try (PreparedStatement stmt = dbConnection.prepareStatement(insert)) {
			stmt.setInt(1, event.moduleId);
			stmt.setInt(2, event.eventCode);
			stmt.setString(3, event.severity);
			stmt.setString(4, event.message);
			stmt.setString(5, event.rawLine);
			stmt.executeUpdate();
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed to persist event: " + ex.getMessage());
		}
	}

	private static List<BmsReading> queryHistoryFromDb(int moduleId, int limit, Instant since) {
		List<BmsReading> result = new ArrayList<>();
		if (dbConnection == null) {
			return result;
		}

		StringBuilder sql = new StringBuilder();
		sql.append("SELECT id, created_at, module_id, voltage_v, current_a, soc_percent, status_code, raw_line ");
		sql.append("FROM bms_readings WHERE (? = 0 OR module_id = ?)");
		if (since != null) {
			sql.append(" AND created_at >= ?");
		}
		sql.append(" ORDER BY id DESC LIMIT ?");

		Map<Long, BmsReading> byId = new LinkedHashMap<>();
		List<Long> ids = new ArrayList<>();

		try (PreparedStatement stmt = dbConnection.prepareStatement(sql.toString())) {
			stmt.setInt(1, moduleId);
			stmt.setInt(2, moduleId);
			int idx = 3;
			if (since != null) {
				stmt.setTimestamp(idx++, Timestamp.from(since));
			}
			stmt.setInt(idx, limit);
			try (java.sql.ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					long id = rs.getLong("id");
					BmsReading reading = new BmsReading();
					Timestamp createdAt = rs.getTimestamp("created_at");
					reading.timestamp = toIsoTimestamp(createdAt);
					reading.moduleId = rs.getInt("module_id");
					reading.voltageV = normalizeVoltage(rs.getDouble("voltage_v"), 0);
					reading.currentA = normalizeCurrent(rs.getDouble("current_a"));
					reading.socPercent = rs.getDouble("soc_percent");
					reading.statusCode = rs.getInt("status_code");
					reading.rawLine = rs.getString("raw_line");
					reading.cellMv = new ArrayList<>();
					byId.put(id, reading);
					ids.add(id);
					result.add(reading);
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed loading history from DB: " + ex.getMessage());
			return result;
		}

		if (ids.isEmpty()) {
			return result;
		}

		StringBuilder placeholders = new StringBuilder();
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) {
				placeholders.append(',');
			}
			placeholders.append('?');
		}

		String cellSql =
			"SELECT reading_id, cell_index, cell_mv FROM bms_cell_readings " +
			"WHERE reading_id IN (" + placeholders + ") ORDER BY reading_id DESC, cell_index ASC";

		try (PreparedStatement stmt = dbConnection.prepareStatement(cellSql)) {
			for (int i = 0; i < ids.size(); i++) {
				stmt.setLong(i + 1, ids.get(i));
			}
			try (java.sql.ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					long readingId = rs.getLong("reading_id");
					BmsReading reading = byId.get(readingId);
					if (reading != null) {
						int cellMv = normalizeCellMv(rs.getInt("cell_mv"));
						if (cellMv > 0) {
							reading.cellMv.add(cellMv);
						}
					}
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed loading cell history from DB: " + ex.getMessage());
		}

		return result;
	}

	private static Map<Integer, ModuleStats> queryStatisticsFromDb(int moduleId, Instant since) {
		Map<Integer, ModuleStats> statsByModule = new LinkedHashMap<>();
		if (dbConnection == null) {
			return statsByModule;
		}

		StringBuilder aggregatesSql = new StringBuilder();
		aggregatesSql.append(
			"SELECT module_id, COUNT(*) AS sample_count, AVG(soc_percent) AS avg_soc, MIN(soc_percent) AS min_soc, MAX(soc_percent) AS max_soc, " +
			"MIN(voltage_v) AS min_voltage, MAX(voltage_v) AS max_voltage, MIN(current_a) AS min_current, MAX(current_a) AS max_current " +
			"FROM bms_readings WHERE (? = 0 OR module_id = ?)"
		);
		if (since != null) {
			aggregatesSql.append(" AND created_at >= ?");
		}
		aggregatesSql.append(" GROUP BY module_id ORDER BY module_id");

		try (PreparedStatement stmt = dbConnection.prepareStatement(aggregatesSql.toString())) {
			stmt.setInt(1, moduleId);
			stmt.setInt(2, moduleId);
			if (since != null) {
				stmt.setTimestamp(3, Timestamp.from(since));
			}
			try (java.sql.ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int module = rs.getInt("module_id");
					ModuleStats stats = new ModuleStats(module);
					stats.sampleCount = rs.getInt("sample_count");
					stats.socSum = rs.getDouble("avg_soc") * Math.max(stats.sampleCount, 1);
					stats.minSoc = rs.getDouble("min_soc");
					stats.maxSoc = rs.getDouble("max_soc");
					stats.minVoltage = rs.getDouble("min_voltage");
					stats.maxVoltage = rs.getDouble("max_voltage");
					stats.minCurrent = rs.getDouble("min_current");
					stats.maxCurrent = rs.getDouble("max_current");
					statsByModule.put(module, stats);
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed loading statistics from DB: " + ex.getMessage());
			return statsByModule;
		}

		StringBuilder latestSql = new StringBuilder();
		latestSql.append("SELECT r.module_id, r.status_code, r.created_at FROM bms_readings r ");
		latestSql.append("JOIN (SELECT module_id, MAX(id) AS max_id FROM bms_readings WHERE (? = 0 OR module_id = ?)");
		if (since != null) {
			latestSql.append(" AND created_at >= ?");
		}
		latestSql.append(" GROUP BY module_id) x ON r.id = x.max_id ORDER BY r.module_id");

		try (PreparedStatement stmt = dbConnection.prepareStatement(latestSql.toString())) {
			stmt.setInt(1, moduleId);
			stmt.setInt(2, moduleId);
			if (since != null) {
				stmt.setTimestamp(3, Timestamp.from(since));
			}
			try (java.sql.ResultSet rs = stmt.executeQuery()) {
				while (rs.next()) {
					int module = rs.getInt("module_id");
					ModuleStats stats = statsByModule.computeIfAbsent(module, ModuleStats::new);
					stats.lastStatusCode = rs.getInt("status_code");
					stats.lastTimestamp = toIsoTimestamp(rs.getTimestamp("created_at"));
				}
			}
		} catch (Exception ex) {
			System.err.println("[BmsApiServer] Failed loading latest status from DB: " + ex.getMessage());
		}

		return statsByModule;
	}

	private static String toIsoTimestamp(Timestamp timestamp) {
		if (timestamp == null) {
			return "";
		}
		return timestamp.toInstant().toString();
	}

	private static long secondsSince(Instant instant) {
		long diff = Instant.now().getEpochSecond() - instant.getEpochSecond();
		return Math.max(diff, 0);
	}

	private static Instant parseSinceCutoff(Map<String, String> query) {
		int minutes = safeInt(query.get("sinceMinutes"), 0);
		if (minutes <= 0) {
			return null;
		}
		int capped = Math.min(minutes, 7 * 24 * 60);
		return Instant.now().minusSeconds((long) capped * 60L);
	}

	private static class HealthHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			List<Integer> modules = new ArrayList<>(latestByModule.keySet());
			Collections.sort(modules);

			StringBuilder sb = new StringBuilder();
			sb.append("{\"status\":\"ok\",\"dbConnected\":").append(dbConnection != null).append(",\"modulesSeen\":[");
			for (int i = 0; i < modules.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(modules.get(i));
			}
			sb.append("],\"allowedModules\":[");
			List<Integer> allowed = new ArrayList<>(allowedModules);
			Collections.sort(allowed);
			for (int i = 0; i < allowed.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(allowed.get(i));
			}
			sb.append("]}");
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class IngestHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			String body = readBody(exchange.getRequestBody()).trim();
			if (body.isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"Empty request body\"}");
				return;
			}

			int accepted = 0;
			int heartbeat = 0;
			int rejected = 0;
			int lastAcceptedModuleId = 0;
			String lastAcceptedPayload = "";
			String sourceAddr = "unknown";
			if (exchange.getRemoteAddress() != null && exchange.getRemoteAddress().getAddress() != null) {
				sourceAddr = exchange.getRemoteAddress().getAddress().getHostAddress();
			}

			String[] lines = body.split("\\r?\\n");
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				IngestResult result = ingestLine(trimmed);
				if (result.accepted) {
					if (result.heartbeat) {
						heartbeat++;
					} else {
						accepted++;
					}
					if (result.moduleId > 0) {
						lastAcceptedModuleId = result.moduleId;
					}
					lastAcceptedPayload = result.normalizedPayload;
				} else {
					rejected++;
				}
			}

			persistIngestSource(sourceAddr, accepted, heartbeat, lastAcceptedPayload, lastAcceptedModuleId);

			String response = "{\"accepted\":" + accepted + ",\"heartbeat\":" + heartbeat + ",\"rejected\":" + rejected + "}";
			writeJson(exchange, 200, response);
		}
	}

	private static class LatestHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int moduleIdFilter = safeInt(query.get("moduleId"), 0);

			List<BmsReading> latest = new ArrayList<>(latestByModule.values());
			latest.sort(Comparator.comparingInt(a -> a.moduleId));
			if (moduleIdFilter > 0) {
				latest.removeIf(reading -> reading.moduleId != moduleIdFilter);
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (int i = 0; i < latest.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(latest.get(i).toJson());
			}
			sb.append(']');

			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class HistoryHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int moduleId = safeInt(query.get("moduleId"), 0);
			int limit = safeInt(query.get("limit"), 200);
			Instant since = parseSinceCutoff(query);
			if (limit < 1) {
				limit = 1;
			}
			if (limit > 2000) {
				limit = 2000;
			}

			List<BmsReading> snapshot;
			if (dbConnection != null) {
				snapshot = queryHistoryFromDb(moduleId, limit, since);
			} else {
				snapshot = new ArrayList<>();
				synchronized (LOCK) {
					for (BmsReading item : history) {
						if (moduleId > 0 && item.moduleId != moduleId) {
							continue;
						}
						if (since != null) {
							try {
								if (Instant.parse(item.timestamp).isBefore(since)) {
									continue;
								}
							} catch (Exception ignored) {
								continue;
							}
						}
						snapshot.add(item);
						if (snapshot.size() >= limit) {
							break;
						}
					}
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (int i = 0; i < snapshot.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(snapshot.get(i).toJson());
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class EventHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int limit = safeInt(query.get("limit"), 200);
			if (limit < 1) {
				limit = 1;
			}
			if (limit > 2000) {
				limit = 2000;
			}

			List<BmsEvent> snapshot = new ArrayList<>();
			snapshot.addAll(buildSystemEvents());
			synchronized (LOCK) {
				for (BmsEvent item : events) {
					if (snapshot.size() >= limit) {
						break;
					}
					snapshot.add(item);
				}
			}

			if (snapshot.size() > limit) {
				snapshot = snapshot.subList(0, limit);
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			for (int i = 0; i < snapshot.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(snapshot.get(i).toJson());
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static List<BmsEvent> buildSystemEvents() {
		List<BmsEvent> result = new ArrayList<>();
		int dataSourceOfflineThresholdSec = parseIntEnv("BMS_CONNECTION_OFFLINE_SECONDS",
			parseIntEnv("BMS_RPI_OFFLINE_SECONDS", 30));
		if (dataSourceOfflineThresholdSec < 1) {
			dataSourceOfflineThresholdSec = 30;
		}

		if (dbWasConnected && !isDbConnectionAlive()) {
			result.add(systemEvent(0, EVENT_DB_DISCONNECTED, "ERROR", "Database connection lost"));
		}

		if (!ingestSources.isEmpty()) {
			boolean anySourceOnline = false;
			for (IngestSourceState state : ingestSources.values()) {
				if (state == null || state.lastSeen == null) {
					continue;
				}
				if (secondsSince(state.lastSeen) <= dataSourceOfflineThresholdSec) {
					anySourceOnline = true;
					break;
				}
			}
			if (!anySourceOnline) {
				result.add(systemEvent(0, EVENT_DATA_SOURCE_DISCONNECTED, "ERROR", "Computer to BMS telemetry connection lost"));
			}
		}

		int bmsOfflineThresholdSec = parseIntEnv("BMS_BMS_OFFLINE_SECONDS", dataSourceOfflineThresholdSec);
		if (bmsOfflineThresholdSec < 1) {
			bmsOfflineThresholdSec = dataSourceOfflineThresholdSec;
		}

		List<Integer> trackedModules = new ArrayList<>();
		if (!allowedModules.isEmpty()) {
			trackedModules.addAll(allowedModules);
		} else {
			trackedModules.addAll(modulesSeen);
		}
		Collections.sort(trackedModules);

		int offlineCount = 0;
		for (Integer moduleId : trackedModules) {
			if (moduleId == null || moduleId < 1 || moduleId > 4) {
				continue;
			}
			BmsReading latest = latestByModule.get(moduleId);
			if (latest == null) {
				offlineCount++;
				result.add(systemEvent(moduleId, EVENT_BMS_DISCONNECTED + moduleId, "WARN", "BMS module " + moduleId + " telemetry lost"));
				continue;
			}

			Instant ts = parseInstantSafe(latest.timestamp);
			if (ts == null || secondsSince(ts) > bmsOfflineThresholdSec) {
				offlineCount++;
				result.add(systemEvent(moduleId, EVENT_BMS_DISCONNECTED + moduleId, "WARN", "BMS module " + moduleId + " telemetry lost"));
			}
		}

		if (!trackedModules.isEmpty() && offlineCount == trackedModules.size()) {
			result.add(systemEvent(0, EVENT_BMS_DISCONNECTED, "ERROR", "BMS connection lost (all tracked modules offline)"));
		}

		return result;
	}

	private static Instant parseInstantSafe(String input) {
		if (input == null || input.trim().isEmpty()) {
			return null;
		}
		try {
			return Instant.parse(input.trim());
		} catch (Exception ex) {
			return null;
		}
	}

	private static BmsEvent systemEvent(int moduleId, int eventCode, String severity, String message) {
		BmsEvent event = new BmsEvent();
		event.timestamp = Instant.now().toString();
		event.moduleId = moduleId;
		event.eventCode = eventCode;
		event.severity = severity;
		event.message = message;
		event.rawLine = "SYSTEM," + eventCode + "," + severity + "," + message;
		return event;
	}

	private static BmsEvent fromTinyBmsEvent(TinyBmsUartSettingsService.TinyBmsEvent bmsEvent, String sourceMode) {
		BmsEvent event = new BmsEvent();
		event.timestamp = Instant.now().toString();
		event.moduleId = 1;
		event.eventCode = bmsEvent.eventCode;
		event.severity = severityForTinyBmsEvent(bmsEvent.eventCode);
		event.message = tinyBmsEventName(bmsEvent.eventCode) +
			" (BMS timestamp=" + bmsEvent.timestamp + ", base=" + bmsEvent.baseTimestamp + ")";
		event.rawLine = "TINYBMS_EVENT," + sourceMode + "," + bmsEvent.baseTimestamp + "," + bmsEvent.timestamp + "," + bmsEvent.eventCode;
		return event;
	}

	private static String severityForTinyBmsEvent(int eventCode) {
		if (eventCode >= 0x01 && eventCode <= 0x0F) {
			return "ERROR";
		}
		if (eventCode >= 0x20 && eventCode <= 0x25) {
			return "WARN";
		}
		return "INFO";
	}

	private static String tinyBmsEventName(int eventCode) {
		switch (eventCode) {
			case 0x01: return "Cell undervoltage";
			case 0x02: return "Cell overvoltage";
			case 0x03: return "Pack undervoltage";
			case 0x04: return "Pack overvoltage";
			case 0x05: return "Charge overcurrent";
			case 0x06: return "Discharge overcurrent";
			case 0x07: return "Short circuit";
			case 0x08: return "MOSFET overtemperature";
			case 0x09: return "PCB overtemperature";
			case 0x0A: return "Cell overtemperature";
			case 0x0B: return "Cell undertemperature";
			case 0x0C: return "Charge temperature high";
			case 0x0D: return "Discharge temperature high";
			case 0x0E: return "External sensor fault";
			case 0x0F: return "Internal error";
			case 0x20: return "Cell balancing warning";
			case 0x21: return "High current warning";
			case 0x22: return "High temperature warning";
			case 0x23: return "Low temperature warning";
			case 0x24: return "High voltage warning";
			case 0x25: return "Low voltage warning";
			case 0x30: return "BMS started";
			case 0x31: return "Charging";
			case 0x32: return "Fully charged";
			case 0x33: return "Discharging";
			case 0x34: return "Regeneration";
			case 0x35: return "Idle";
			case 0x36: return "Balancing";
			default: return "TinyBMS event 0x" + String.format(Locale.ROOT, "%02X", eventCode);
		}
	}

	private static boolean isDbConnectionAlive() {
		Connection connection = dbConnection;
		if (connection == null) {
			return false;
		}
		try {
			return connection.isValid(1);
		} catch (Exception ex) {
			return false;
		}
	}

	private static class StatisticsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int moduleId = safeInt(query.get("moduleId"), 0);
			Instant since = parseSinceCutoff(query);

			Map<Integer, ModuleStats> statsByModule;
			if (dbConnection != null) {
				statsByModule = queryStatisticsFromDb(moduleId, since);
			} else {
				statsByModule = new LinkedHashMap<>();
				synchronized (LOCK) {
					for (BmsReading reading : history) {
						if (moduleId > 0 && reading.moduleId != moduleId) {
							continue;
						}
						if (since != null) {
							try {
								if (Instant.parse(reading.timestamp).isBefore(since)) {
									continue;
								}
							} catch (Exception ignored) {
								continue;
							}
						}
						ModuleStats stats = statsByModule.get(reading.moduleId);
						if (stats == null) {
							stats = new ModuleStats(reading.moduleId);
							statsByModule.put(reading.moduleId, stats);
						}
						stats.accept(reading);
					}
				}
			}

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			List<Integer> keys = new ArrayList<>(statsByModule.keySet());
			Collections.sort(keys);
			for (int i = 0; i < keys.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(statsByModule.get(keys.get(i)).toJson());
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}
	}

	private static class BmsConnectionHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			int offlineThresholdSec = parseIntEnv("BMS_CONNECTION_OFFLINE_SECONDS",
				parseIntEnv("BMS_BMS_OFFLINE_SECONDS", parseIntEnv("BMS_RPI_OFFLINE_SECONDS", 30)));
			if (offlineThresholdSec < 1) {
				offlineThresholdSec = 30;
			}

			StringBuilder sourcesJson = new StringBuilder();
			sourcesJson.append('[');
			boolean firstSource = true;
			boolean anyOnline = false;

			if (dbConnection != null) {
				String sql =
					"SELECT source_addr, last_seen, accepted_count, last_module_id, last_payload " +
					"FROM bms_ingest_sources ORDER BY last_seen DESC LIMIT 20";
				try (Statement stmt = dbConnection.createStatement();
					 java.sql.ResultSet rs = stmt.executeQuery(sql)) {
					while (rs.next()) {
						Timestamp lastSeenTs = rs.getTimestamp("last_seen");
						String lastSeen = toIsoTimestamp(lastSeenTs);
						long seconds = lastSeenTs == null ? Long.MAX_VALUE : secondsSince(lastSeenTs.toInstant());
						boolean online = seconds <= offlineThresholdSec;
						if (online) {
							anyOnline = true;
						}

						if (!firstSource) {
							sourcesJson.append(',');
						}
						firstSource = false;

						sourcesJson.append("{\"source\":\"").append(escapeJson(rs.getString("source_addr"))).append("\"");
						sourcesJson.append(",\"lastSeen\":\"").append(escapeJson(lastSeen)).append("\"");
						sourcesJson.append(",\"secondsSinceSeen\":").append(seconds == Long.MAX_VALUE ? -1 : seconds);
						sourcesJson.append(",\"online\":").append(online);
						sourcesJson.append(",\"acceptedCount\":").append(rs.getLong("accepted_count"));
						int lastModule = rs.getInt("last_module_id");
						if (rs.wasNull()) {
							sourcesJson.append(",\"lastModuleId\":null");
						} else {
							sourcesJson.append(",\"lastModuleId\":").append(lastModule);
						}
						sourcesJson.append(",\"lastPayload\":\"").append(escapeJson(rs.getString("last_payload"))).append("\"}");
					}
				} catch (Exception ex) {
					System.err.println("[BmsApiServer] Failed loading BMS connection sources from DB: " + ex.getMessage());
				}
			} else {
				List<Map.Entry<String, IngestSourceState>> sourceEntries = new ArrayList<>(ingestSources.entrySet());
				sourceEntries.sort((a, b) -> {
					Instant aSeen = a.getValue() == null ? null : a.getValue().lastSeen;
					Instant bSeen = b.getValue() == null ? null : b.getValue().lastSeen;
					if (aSeen == null && bSeen == null) {
						return 0;
					}
					if (aSeen == null) {
						return 1;
					}
					if (bSeen == null) {
						return -1;
					}
					return bSeen.compareTo(aSeen);
				});

				int max = Math.min(sourceEntries.size(), 20);
				for (int i = 0; i < max; i++) {
					Map.Entry<String, IngestSourceState> entry = sourceEntries.get(i);
					String source = entry.getKey();
					IngestSourceState state = entry.getValue();
					if (state == null) {
						continue;
					}

					long seconds = state.lastSeen == null ? Long.MAX_VALUE : secondsSince(state.lastSeen);
					boolean online = seconds <= offlineThresholdSec;
					if (online) {
						anyOnline = true;
					}

					if (!firstSource) {
						sourcesJson.append(',');
					}
					firstSource = false;

					sourcesJson.append("{\"source\":\"").append(escapeJson(source)).append("\"");
					sourcesJson.append(",\"lastSeen\":\"").append(state.lastSeen == null ? "" : escapeJson(state.lastSeen.toString())).append("\"");
					sourcesJson.append(",\"secondsSinceSeen\":").append(seconds == Long.MAX_VALUE ? -1 : seconds);
					sourcesJson.append(",\"online\":").append(online);
					sourcesJson.append(",\"acceptedCount\":").append(state.acceptedCount);
					if (state.lastModuleId > 0) {
						sourcesJson.append(",\"lastModuleId\":").append(state.lastModuleId);
					} else {
						sourcesJson.append(",\"lastModuleId\":null");
					}
					sourcesJson.append(",\"lastPayload\":\"").append(escapeJson(state.lastPayload)).append("\"}");
				}
			}
			sourcesJson.append(']');

			Map<Integer, Instant> moduleLastSeen = new HashMap<>();
			if (dbConnection != null) {
				String moduleSql =
					"SELECT module_id, MAX(created_at) AS last_seen FROM bms_readings WHERE module_id = 1 GROUP BY module_id";
				try (Statement stmt = dbConnection.createStatement();
					 java.sql.ResultSet rs = stmt.executeQuery(moduleSql)) {
					while (rs.next()) {
						Timestamp ts = rs.getTimestamp("last_seen");
						if (ts != null) {
							moduleLastSeen.put(rs.getInt("module_id"), ts.toInstant());
						}
					}
				} catch (Exception ex) {
					System.err.println("[BmsApiServer] Failed loading module last-seen from DB: " + ex.getMessage());
				}
			} else {
				for (Map.Entry<Integer, BmsReading> entry : latestByModule.entrySet()) {
					try {
						moduleLastSeen.put(entry.getKey(), Instant.parse(entry.getValue().timestamp));
					} catch (Exception ignored) {
						// ignore malformed timestamps
					}
				}
			}

			StringBuilder modulesJson = new StringBuilder();
			modulesJson.append('[');
			int moduleId = 1;
			Instant lastSeen = moduleLastSeen.get(moduleId);
			long seconds = lastSeen == null ? Long.MAX_VALUE : secondsSince(lastSeen);
			boolean online = seconds <= offlineThresholdSec;
			if (online) {
				anyOnline = true;
			}
			modulesJson.append("{\"moduleId\":").append(moduleId);
			if (lastSeen == null) {
				modulesJson.append(",\"lastSeen\":\"\"");
				modulesJson.append(",\"secondsSinceSeen\":-1");
			} else {
				modulesJson.append(",\"lastSeen\":\"").append(escapeJson(lastSeen.toString())).append("\"");
				modulesJson.append(",\"secondsSinceSeen\":").append(seconds);
			}
			modulesJson.append(",\"online\":").append(online).append('}');
			modulesJson.append(']');

			String json =
				"{\"offlineThresholdSec\":" + offlineThresholdSec +
				",\"overallOnline\":" + anyOnline +
				",\"sources\":" + sourcesJson +
				",\"modules\":" + modulesJson + "}";
			writeJson(exchange, 200, json);
		}
	}

	private static class CellSettingsHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}

			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				handleGet(exchange);
				return;
			}
			if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				handlePost(exchange);
				return;
			}

			writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
		}

		private void handleGet(HttpExchange exchange) throws IOException {
			Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
			int filterModule = safeInt(query.get("moduleId"), 0);

			StringBuilder sb = new StringBuilder();
			sb.append('[');
			boolean firstModule = true;
			for (int moduleId = 1; moduleId <= 4; moduleId++) {
				if (filterModule > 0 && moduleId != filterModule) {
					continue;
				}
				if (!firstModule) {
					sb.append(',');
				}
				firstModule = false;
				sb.append(moduleSettingsToJson(moduleId));
			}
			sb.append(']');
			writeJson(exchange, 200, sb.toString());
		}

		private void handlePost(HttpExchange exchange) throws IOException {
			String body = readBody(exchange.getRequestBody());
			Map<String, String> params = parseQuery(body);

			if (params.isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"Expected form body: moduleId,key,value\"}");
				return;
			}

			int moduleId = safeInt(params.get("moduleId"), 0);
			String key = params.get("key");
			double value = safeDouble(params.get("value"), Double.NaN);

			if (moduleId < 1 || moduleId > 4) {
				writeJson(exchange, 400, "{\"error\":\"moduleId must be between 1 and 4\"}");
				return;
			}
			if (!allowedModules.isEmpty() && !allowedModules.contains(moduleId)) {
				writeJson(exchange, 403, "{\"error\":\"moduleId blocked by BMS_ALLOWED_MODULES\"}");
				return;
			}
			if (key == null || key.trim().isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"key is required\"}");
				return;
			}

			SettingDefinition definition = getSettingDefinition(key.trim());
			if (definition == null) {
				writeJson(exchange, 400, "{\"error\":\"unknown setting key\"}");
				return;
			}
			if (!definition.writable) {
				writeJson(exchange, 400, "{\"error\":\"setting is read-only\"}");
				return;
			}
			if (Double.isNaN(value)) {
				writeJson(exchange, 400, "{\"error\":\"value must be numeric\"}");
				return;
			}
			if (value < definition.minValue || value > definition.maxValue) {
				writeJson(
					exchange,
					400,
					"{\"error\":\"value out of range\",\"min\":" + definition.minValue + ",\"max\":" + definition.maxValue + "}"
				);
				return;
			}

			cellSettingsByModule.computeIfAbsent(moduleId, ignored -> new ConcurrentHashMap<>()).put(definition.key, value);
			persistSetting(moduleId, definition, value);

			writeJson(exchange, 200, moduleSettingsToJson(moduleId));
		}
	}

	private static class RuntimeConfigHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}

			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				handleGet(exchange);
				return;
			}
			if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				handlePost(exchange);
				return;
			}

			writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
		}

		private void handleGet(HttpExchange exchange) throws IOException {
			Path envPath = resolveEnvPath();
			String serialPort = readConfigValue("SERIAL_PORT", firstNonBlank(System.getenv("SERIAL_PORT"), "COM5"));
			String apiIngest = readConfigValue("BMS_API_INGEST_URL", firstNonBlank(System.getenv("BMS_API_INGEST_URL"), "http://127.0.0.1:8090/api/ingest"));
			List<String> availablePorts = listAvailableSerialPorts();

			String json = "{\"serialPort\":\"" + escapeJson(serialPort) + "\"" +
				",\"ingestUrl\":\"" + escapeJson(apiIngest) + "\"" +
				",\"envPath\":\"" + escapeJson(envPath.toAbsolutePath().toString()) + "\"" +
				",\"envExists\":" + Files.exists(envPath) +
				",\"bmsConnected\":" + !modulesSeen.isEmpty() +
				",\"availablePorts\":" + stringListToJson(availablePorts) + "}";
			writeJson(exchange, 200, json);
		}

		private void handlePost(HttpExchange exchange) throws IOException {
			String body = readBody(exchange.getRequestBody());
			Map<String, String> params = parseQuery(body);
			String serialPort = firstNonBlank(params.get("serialPort"), params.get("port"), "");
			if (serialPort.isEmpty()) {
				writeJson(exchange, 400, "{\"error\":\"serialPort is required\"}");
				return;
			}

			String normalizedPort = serialPort.trim().toUpperCase(Locale.ROOT);
			if (!isSupportedSerialPort(normalizedPort)) {
				writeJson(exchange, 400, "{\"error\":\"serialPort must look like COM3, COM5 or SIMULATED\"}");
				return;
			}

			writeConfigValue("SERIAL_PORT", normalizedPort);
			writeJson(
				exchange,
				200,
				"{\"ok\":true,\"serialPort\":\"" + escapeJson(normalizedPort) + "\",\"message\":\"SERIAL_PORT saved to .env. Restart UART sender to use the new port.\"}"
			);
		}
	}

	private static class UartControlHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}

			if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 200, uartStatusJson("ok"));
				return;
			}
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> params = parseQuery(readBody(exchange.getRequestBody()));
			String action = firstNonBlank(params.get("action"), "").toLowerCase(Locale.ROOT);
			if ("start".equals(action)) {
				String requestedPort = firstNonBlank(params.get("serialPort"), params.get("port"), readConfigValue("SERIAL_PORT", "COM5"));
				try {
					startUartProcess(requestedPort);
					writeJson(exchange, 200, uartStatusJson("started"));
				} catch (Exception ex) {
					writeJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
				}
				return;
			}
			if ("stop".equals(action)) {
				stopAllUartProcesses();
				writeJson(exchange, 200, uartStatusJson("stopped"));
				return;
			}

			writeJson(exchange, 400, "{\"error\":\"action must be start or stop\"}");
		}
	}

	private static class BmsControlHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> params = parseQuery(readBody(exchange.getRequestBody()));
			String action = firstNonBlank(params.get("action"), "").trim().toLowerCase(Locale.ROOT);
			String serialPort = firstNonBlank(params.get("serialPort"), params.get("port"), readConfigValue("SERIAL_PORT", "COM5")).trim().toUpperCase(Locale.ROOT);

			if (!isSupportedSerialPort(serialPort)) {
				writeJson(exchange, 400, "{\"error\":\"serialPort must look like COM3, COM5 or SIMULATED\"}");
				return;
			}
			if ("SIMULATED".equals(serialPort)) {
				writeJson(exchange, 400, "{\"error\":\"Maintenance commands are unavailable in SIMULATED mode.\"}");
				return;
			}

			String message;
			boolean restartUart = releaseSerialPortForMaintenance(serialPort);
			try (TinyBmsUartSettingsService uart = new TinyBmsUartSettingsService(serialPort, parseIntEnv("SERIAL_BAUD", 115200))) {
				switch (action) {
					case "reset":
						uart.resetBms();
						message = "BMS reset command sent.";
						break;
					case "clear-events":
						uart.clearEvents();
						message = "Clear events command sent.";
						break;
					case "clear-statistics":
						uart.clearStatistics();
						message = "Clear statistics command sent.";
						break;
					default:
						writeJson(exchange, 400, "{\"error\":\"action must be reset, clear-events or clear-statistics\"}");
						return;
				}
				if (!uart.lastResponseHex().isEmpty()) {
					message += " Response: " + uart.lastResponseHex() + ".";
				}
			} catch (Exception ex) {
				if (restartUart) {
					try {
						startUartProcess(serialPort);
					} catch (Exception restartEx) {
						appendUartLog("[UART] Failed to restart sender after maintenance command: " + restartEx.getMessage());
					}
				}
				writeJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
				return;
			}
			if (restartUart) {
				try {
					startUartProcess(serialPort);
					message += " UART sender restarted.";
				} catch (Exception restartEx) {
					message += " UART sender restart failed: " + restartEx.getMessage();
				}
			}

			writeJson(exchange, 200, "{\"ok\":true,\"action\":\"" + escapeJson(action) + "\",\"serialPort\":\"" + escapeJson(serialPort) + "\",\"message\":\"" + escapeJson(message) + "\"}");
		}
	}

	private static class BmsEventsReadHandler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			if (handleCorsAndPreflight(exchange)) {
				return;
			}
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
				return;
			}

			Map<String, String> params = parseQuery(readBody(exchange.getRequestBody()));
			String mode = firstNonBlank(params.get("mode"), "newest").trim().toLowerCase(Locale.ROOT);
			String serialPort = firstNonBlank(params.get("serialPort"), params.get("port"), readConfigValue("SERIAL_PORT", "COM5")).trim().toUpperCase(Locale.ROOT);
			if (!isSupportedSerialPort(serialPort)) {
				writeJson(exchange, 400, "{\"error\":\"serialPort must look like COM3, COM5 or SIMULATED\"}");
				return;
			}
			if ("SIMULATED".equals(serialPort)) {
				writeJson(exchange, 400, "{\"error\":\"BMS event reads are unavailable in SIMULATED mode.\"}");
				return;
			}
			if (!"newest".equals(mode) && !"all".equals(mode)) {
				writeJson(exchange, 400, "{\"error\":\"mode must be newest or all\"}");
				return;
			}

			boolean restartUart = releaseSerialPortForMaintenance(serialPort);
			List<BmsEvent> imported = new ArrayList<>();
			try (TinyBmsUartSettingsService uart = new TinyBmsUartSettingsService(serialPort, parseIntEnv("SERIAL_BAUD", 115200))) {
				List<TinyBmsUartSettingsService.TinyBmsEvent> bmsEvents;
				if ("all".equals(mode)) {
					bmsEvents = uart.readAllEvents();
				} else {
					bmsEvents = uart.readNewestEvents();
				}

				for (TinyBmsUartSettingsService.TinyBmsEvent bmsEvent : bmsEvents) {
					BmsEvent event = fromTinyBmsEvent(bmsEvent, mode);
					imported.add(event);
					synchronized (LOCK) {
						events.addFirst(event);
						while (events.size() > MAX_EVENTS) {
							events.removeLast();
						}
					}
					persistEvent(event);
				}
			} catch (Exception ex) {
				if (restartUart) {
					try {
						startUartProcess(serialPort);
					} catch (Exception restartEx) {
						appendUartLog("[UART] Failed to restart sender after BMS event read: " + restartEx.getMessage());
					}
				}
				writeJson(exchange, 500, "{\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
				return;
			}

			String message = "Read " + imported.size() + " BMS event(s).";
			if (restartUart) {
				try {
					startUartProcess(serialPort);
					message += " UART sender restarted.";
				} catch (Exception restartEx) {
					message += " UART sender restart failed: " + restartEx.getMessage();
				}
			}

			StringBuilder json = new StringBuilder();
			json.append("{\"ok\":true,\"mode\":\"").append(escapeJson(mode)).append("\",")
				.append("\"serialPort\":\"").append(escapeJson(serialPort)).append("\",")
				.append("\"message\":\"").append(escapeJson(message)).append("\",")
				.append("\"events\":[");
			for (int i = 0; i < imported.size(); i++) {
				if (i > 0) {
					json.append(',');
				}
				json.append(imported.get(i).toJson());
			}
			json.append("]}");
			writeJson(exchange, 200, json.toString());
		}
	}

	private static boolean handleCorsAndPreflight(HttpExchange exchange) throws IOException {
		Headers headers = exchange.getResponseHeaders();
		headers.add("Access-Control-Allow-Origin", "*");
		headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
		headers.add("Access-Control-Allow-Headers", "Content-Type");
		if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
			return true;
		}
		return false;
	}

	private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		Headers headers = exchange.getResponseHeaders();
		headers.set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static String readBody(InputStream is) throws IOException {
		byte[] bytes = is.readAllBytes();
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static String escapeJson(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r");
	}

	private static boolean isInteger(String input) {
		if (input == null || input.trim().isEmpty()) {
			return false;
		}
		try {
			Integer.parseInt(input.trim());
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static int safeInt(String input, int fallback) {
		if (input == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(input.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static double safeDouble(String input, double fallback) {
		if (input == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(input.trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.trim().isEmpty() ? fallback : value.trim();
	}

	private static String resolveDbUrl() {
		String explicit = env("BMS_DB_URL", "");
		if (!explicit.isEmpty()) {
			return explicit;
		}

		String host = env("DB_HOST", "127.0.0.1");
		String port = env("DB_PORT", "3306");
		String dbName = env("DB_NAME", "bms");
		return "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true";
	}

	private static String resolveDbUser() {
		return firstNonBlank(System.getenv("BMS_DB_USER"), System.getenv("DB_USER"), "root");
	}

	private static String resolveDbPass() {
		return firstNonBlank(System.getenv("BMS_DB_PASS"), System.getenv("DB_PASSWORD"), "");
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) {
				return value.trim();
			}
		}
		return "";
	}

	private static SettingDefinition getSettingDefinition(String key) {
		if (key == null) {
			return null;
		}
		for (SettingDefinition definition : SETTING_DEFINITIONS) {
			if (definition.key.equals(key)) {
				return definition;
			}
		}
		return null;
	}

	private static String moduleSettingsToJson(int moduleId) {
		Map<String, Double> values = cellSettingsByModule.computeIfAbsent(moduleId, ignored -> new ConcurrentHashMap<>());

		StringBuilder sb = new StringBuilder();
		sb.append("{\"moduleId\":").append(moduleId).append(",\"settings\":[");
		for (int i = 0; i < SETTING_DEFINITIONS.length; i++) {
			SettingDefinition definition = SETTING_DEFINITIONS[i];
			if (i > 0) {
				sb.append(',');
			}
			double currentValue = values.containsKey(definition.key)
				? values.get(definition.key)
				: definition.defaultValue;
			sb.append("{\"key\":\"").append(escapeJson(definition.key)).append("\"");
			sb.append(",\"label\":\"").append(escapeJson(definition.label)).append("\"");
			sb.append(",\"unit\":\"").append(escapeJson(definition.unit)).append("\"");
			sb.append(",\"min\":").append(String.format(Locale.US, "%.4f", definition.minValue));
			sb.append(",\"max\":").append(String.format(Locale.US, "%.4f", definition.maxValue));
			sb.append(",\"value\":").append(String.format(Locale.US, "%.4f", currentValue));
			sb.append(",\"writable\":").append(definition.writable);
			sb.append('}');
		}
		sb.append("]}");
		return sb.toString();
	}

	private static Path resolveEnvPath() {
		return Path.of(".env").toAbsolutePath().normalize();
	}

	private static String readConfigValue(String key, String fallback) {
		Path envPath = resolveEnvPath();
		if (!Files.exists(envPath)) {
			return fallback;
		}
		try {
			for (String line : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
				if (line == null) {
					continue;
				}
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				int eq = trimmed.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String currentKey = trimmed.substring(0, eq).trim();
				if (!key.equals(currentKey)) {
					continue;
				}
				return trimmed.substring(eq + 1).trim();
			}
		} catch (IOException ex) {
			System.err.println("[BmsApiServer] Failed reading .env: " + ex.getMessage());
		}
		return fallback;
	}

	private static synchronized void writeConfigValue(String key, String value) throws IOException {
		Path envPath = resolveEnvPath();
		List<String> lines = Files.exists(envPath)
			? new ArrayList<>(Files.readAllLines(envPath, StandardCharsets.UTF_8))
			: new ArrayList<>();

		boolean updated = false;
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line == null) {
				continue;
			}
			String trimmed = line.trim();
			if (trimmed.startsWith(key + "=")) {
				lines.set(i, key + "=" + value);
				updated = true;
				break;
			}
		}

		if (!updated) {
			if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
				lines.add("");
			}
			lines.add(key + "=" + value);
		}

		Files.write(envPath, lines, StandardCharsets.UTF_8);
	}

	private static synchronized void startUartProcess(String port) throws IOException {
		String normalizedPort = firstNonBlank(port, "COM5").trim().toUpperCase(Locale.ROOT);
		if (!isSupportedSerialPort(normalizedPort)) {
			throw new IOException("serialPort must look like COM3, COM5 or SIMULATED");
		}
		stopAllUartProcesses();

		Path workDir = Path.of(".").toAbsolutePath().normalize();
		ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "run_uart_sender.bat", "--no-pause", normalizedPort, "--require-open");
		builder.directory(workDir.toFile());
		builder.redirectErrorStream(true);

		appendUartLog("[UART] Starting sender on " + normalizedPort);
		Process process = builder.start();
		uartProcess = process;
		uartProcessPort = normalizedPort;
		uartStartedAt = Instant.now();
		startUartLogPump(process);
		try {
			Thread.sleep(4000L);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("UART start interrupted.", ex);
		}
		if (!process.isAlive()) {
			uartProcess = null;
			uartProcessPort = "";
			uartStartedAt = null;
			throw new IOException("UART sender exited immediately. Check port " + normalizedPort + " and logs below.");
		}
	}

	private static synchronized void stopUartProcess() {
		Process process = uartProcess;
		uartProcess = null;
		uartProcessPort = "";
		uartStartedAt = null;
		if (process != null) {
			appendUartLog("[UART] Stopping sender");
			stopProcessTree(process.toHandle(), "[UART] Stopping sender process");
		}
	}

	private static boolean releaseSerialPortForMaintenance(String serialPort) {
		boolean shouldRestart = false;
		synchronized (BmsApiServer.class) {
			shouldRestart = uartProcess != null && serialPort.equalsIgnoreCase(uartProcessPort);
			if (shouldRestart) {
				stopUartProcess();
			}
		}

		String normalizedPort = firstNonBlank(serialPort, "").trim().toUpperCase(Locale.ROOT);
		stopExternalUartProcesses("", "[UART] Stopping external sender before maintenance command on " + normalizedPort);

		try {
			Thread.sleep(800L);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		waitForSerialPortAvailable(normalizedPort, parseIntEnv("SERIAL_BAUD", 115200), 5000L);
		return shouldRestart;
	}

	private static synchronized void stopAllUartProcesses() {
		stopUartProcess();
		stopExternalUartProcesses("", "[UART] Stopping external UART sender");
	}

	private static void stopExternalUartProcesses(String portFilter, String logMessage) {
		String normalizedFilter = firstNonBlank(portFilter, "").trim().toUpperCase(Locale.ROOT);
		String workspace = Path.of(".").toAbsolutePath().normalize().toString().toUpperCase(Locale.ROOT);
		ProcessHandle.allProcesses()
			.filter(handle -> handle.pid() != ProcessHandle.current().pid())
			.filter(handle -> {
				String commandLine = handle.info().commandLine().orElse("").toUpperCase(Locale.ROOT);
				boolean isJavaSender = commandLine.contains("BMSUARTSENDER");
				boolean isBatchSender = commandLine.contains("RUN_UART_SENDER.BAT");
				if (!isJavaSender && !isBatchSender) {
					return false;
				}
				if (isBatchSender && !commandLine.contains(workspace)) {
					return false;
				}
				return normalizedFilter.isEmpty()
					|| commandLine.contains(normalizedFilter)
					|| commandLine.contains("--PORT=" + normalizedFilter);
			})
			.forEach(handle -> {
				appendUartLog(logMessage + " (pid " + handle.pid() + ")");
				stopProcessTree(handle, logMessage);
			});
	}

	private static void stopProcessTree(ProcessHandle root, String logPrefix) {
		if (root == null) {
			return;
		}
		List<ProcessHandle> handles = new ArrayList<>();
		root.descendants().forEach(handles::add);
		Collections.reverse(handles);
		handles.add(root);

		for (ProcessHandle handle : handles) {
			if (!handle.isAlive()) {
				continue;
			}
			appendUartLog(logPrefix + " pid " + handle.pid());
			handle.destroy();
		}

		long deadline = System.currentTimeMillis() + 3000L;
		for (ProcessHandle handle : handles) {
			while (handle.isAlive() && System.currentTimeMillis() < deadline) {
				try {
					Thread.sleep(50L);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		for (ProcessHandle handle : handles) {
			if (handle.isAlive()) {
				appendUartLog(logPrefix + " force kill pid " + handle.pid());
				handle.destroyForcibly();
			}
		}
	}

	private static void waitForSerialPortAvailable(String serialPort, int baudRate, long timeoutMillis) {
		if (!isSupportedSerialPort(serialPort) || "SIMULATED".equalsIgnoreCase(serialPort)) {
			return;
		}
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			SerialPort probe = SerialPort.getCommPort(serialPort);
			probe.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
			probe.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
			if (probe.openPort(500)) {
				probe.closePort();
				appendUartLog("[UART] " + serialPort + " is available for maintenance command");
				return;
			}
			try {
				Thread.sleep(150L);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		appendUartLog("[UART] " + serialPort + " did not become available before maintenance command");
	}

	private static void startUartLogPump(Process process) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					appendUartLog(line);
				}
			} catch (IOException ex) {
				appendUartLog("[UART] Log pump error: " + ex.getMessage());
			} finally {
				try {
					int exitCode = process.waitFor();
					appendUartLog("[UART] Sender exited with code " + exitCode);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				if (uartProcess == process) {
					uartProcess = null;
					uartProcessPort = "";
					uartStartedAt = null;
				}
			}
		}, "uart-log-pump");
		thread.setDaemon(true);
		thread.start();
	}

	private static void appendUartLog(String line) {
		if (line == null) {
			return;
		}
		synchronized (uartLogs) {
			uartLogs.addLast(line);
			while (uartLogs.size() > MAX_UART_LOG_LINES) {
				uartLogs.removeFirst();
			}
		}
	}

	private static String uartStatusJson(String state) {
		Process process = uartProcess;
		boolean running = process != null && process.isAlive();
		StringBuilder logsJson = new StringBuilder();
		logsJson.append('[');
		synchronized (uartLogs) {
			boolean first = true;
			for (String line : uartLogs) {
				if (!first) {
					logsJson.append(',');
				}
				first = false;
				logsJson.append('"').append(escapeJson(line)).append('"');
			}
		}
		logsJson.append(']');

		String startedAt = uartStartedAt == null ? "" : uartStartedAt.toString();
		return "{\"state\":\"" + escapeJson(state) + "\"" +
			",\"running\":" + running +
			",\"serialPort\":\"" + escapeJson(uartProcessPort) + "\"" +
			",\"startedAt\":\"" + escapeJson(startedAt) + "\"" +
			",\"logs\":" + logsJson + "}";
	}

	private static Set<Integer> parseAllowedModules(String value) {
		Set<Integer> result = new HashSet<>();
		if (value == null || value.trim().isEmpty()) {
			return result;
		}
		String[] parts = value.split(",");
		for (String part : parts) {
			int module = safeInt(part, 0);
			if (module >= 1 && module <= 4) {
				result.add(module);
			}
		}
		return result;
	}

	private static int parseIntEnv(String name, int fallback) {
		return safeInt(System.getenv(name), fallback);
	}

	private static double parseDoubleEnv(String name, double fallback) {
		String value = System.getenv(name);
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private static boolean isSupportedSerialPort(String value) {
		if (value == null) {
			return false;
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		return normalized.matches("COM\\d+") || "SIMULATED".equals(normalized);
	}

	private static List<String> listAvailableSerialPorts() {
		Set<String> ports = new HashSet<>();
		ports.add("SIMULATED");

		try {
			Process process = new ProcessBuilder("reg", "query", "HKEY_LOCAL_MACHINE\\HARDWARE\\DEVICEMAP\\SERIALCOMM")
				.redirectErrorStream(true)
				.start();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String normalized = line.trim().toUpperCase(Locale.ROOT);
					int comIndex = normalized.indexOf("COM");
					if (comIndex < 0) {
						continue;
					}
					String candidate = normalized.substring(comIndex).trim();
					int end = 3;
					while (end < candidate.length() && Character.isDigit(candidate.charAt(end))) {
						end++;
					}
					String port = candidate.substring(0, end);
					if (port.matches("COM\\d+")) {
						ports.add(port);
					}
				}
			}
			process.waitFor(2, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// Best-effort only. We still return SIMULATED.
		}

		List<String> sorted = new ArrayList<>(ports);
		sorted.sort((a, b) -> {
			if ("SIMULATED".equals(a)) return -1;
			if ("SIMULATED".equals(b)) return 1;
			return a.compareTo(b);
		});
		return sorted;
	}

	private static String stringListToJson(List<String> values) {
		StringBuilder sb = new StringBuilder();
		sb.append('[');
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append('"').append(escapeJson(values.get(i))).append('"');
		}
		sb.append(']');
		return sb.toString();
	}

	private static Map<String, String> parseQuery(String query) {
		Map<String, String> result = new HashMap<>();
		if (query == null || query.isEmpty()) {
			return result;
		}
		String[] pairs = query.split("&");
		for (String pair : pairs) {
			String[] kv = pair.split("=", 2);
			String key = urlDecode(kv[0]);
			String value = kv.length > 1 ? urlDecode(kv[1]) : "";
			result.put(key, value);
		}
		return result;
	}

	private static String urlDecode(String input) {
		return URLDecoder.decode(input, StandardCharsets.UTF_8);
	}

	private static class BmsReading {
		String timestamp;
		int moduleId;
		double voltageV;
		double currentA;
		double socPercent;
		int statusCode;
		List<Integer> cellMv;
		String rawLine;

		String toJson() {
			StringBuilder sb = new StringBuilder();
			sb.append("{\"timestamp\":\"").append(escapeJson(timestamp)).append("\"");
			sb.append(",\"moduleId\":").append(moduleId);
			sb.append(",\"voltageV\":").append(String.format(Locale.US, "%.3f", voltageV));
			sb.append(",\"currentA\":").append(String.format(Locale.US, "%.3f", currentA));
			sb.append(",\"socPercent\":").append(String.format(Locale.US, "%.3f", socPercent));
			sb.append(",\"statusCode\":").append(statusCode);
			sb.append(",\"cellsMv\":[");
			for (int i = 0; i < cellMv.size(); i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(cellMv.get(i));
			}
			sb.append(']');
			sb.append(",\"rawLine\":\"").append(escapeJson(rawLine)).append("\"}");
			return sb.toString();
		}
	}

	private static class IngestResult {
		final boolean accepted;
		final boolean heartbeat;
		final int moduleId;
		final String normalizedPayload;

		private IngestResult(boolean accepted, boolean heartbeat, int moduleId, String normalizedPayload) {
			this.accepted = accepted;
			this.heartbeat = heartbeat;
			this.moduleId = moduleId;
			this.normalizedPayload = normalizedPayload;
		}

		static IngestResult accepted(int moduleId, boolean heartbeat, String normalizedPayload) {
			return new IngestResult(true, heartbeat, moduleId, normalizedPayload);
		}

		static IngestResult rejected() {
			return new IngestResult(false, false, 0, "");
		}
	}

	private static class IngestSourceState {
		Instant lastSeen;
		long acceptedCount;
		int lastModuleId;
		String lastPayload = "";
	}

	private static class SettingDefinition {
		final String key;
		final String label;
		final String unit;
		final double minValue;
		final double maxValue;
		final double defaultValue;
		final boolean writable;

		SettingDefinition(String key, String label, String unit, double minValue, double maxValue, double defaultValue, boolean writable) {
			this.key = key;
			this.label = label;
			this.unit = unit;
			this.minValue = minValue;
			this.maxValue = maxValue;
			this.defaultValue = defaultValue;
			this.writable = writable;
		}
	}

	private static class ModuleStats {
		final int moduleId;
		int sampleCount;
		double socSum;
		double minSoc = Double.MAX_VALUE;
		double maxSoc = -Double.MAX_VALUE;
		double minVoltage = Double.MAX_VALUE;
		double maxVoltage = -Double.MAX_VALUE;
		double minCurrent = Double.MAX_VALUE;
		double maxCurrent = -Double.MAX_VALUE;
		int lastStatusCode;
		String lastTimestamp = "";

		ModuleStats(int moduleId) {
			this.moduleId = moduleId;
		}

		void accept(BmsReading reading) {
			sampleCount++;
			socSum += reading.socPercent;
			minSoc = Math.min(minSoc, reading.socPercent);
			maxSoc = Math.max(maxSoc, reading.socPercent);
			minVoltage = Math.min(minVoltage, reading.voltageV);
			maxVoltage = Math.max(maxVoltage, reading.voltageV);
			minCurrent = Math.min(minCurrent, reading.currentA);
			maxCurrent = Math.max(maxCurrent, reading.currentA);
			lastStatusCode = reading.statusCode;
			lastTimestamp = reading.timestamp;
		}

		String toJson() {
			double avgSoc = sampleCount == 0 ? 0.0 : socSum / sampleCount;
			return "{\"moduleId\":" + moduleId +
				",\"sampleCount\":" + sampleCount +
				",\"avgSoc\":" + String.format(Locale.US, "%.3f", avgSoc) +
				",\"minSoc\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : minSoc) +
				",\"maxSoc\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : maxSoc) +
				",\"minVoltage\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : minVoltage) +
				",\"maxVoltage\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : maxVoltage) +
				",\"minCurrent\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : minCurrent) +
				",\"maxCurrent\":" + String.format(Locale.US, "%.3f", sampleCount == 0 ? 0.0 : maxCurrent) +
				",\"lastStatusCode\":" + lastStatusCode +
				",\"lastTimestamp\":\"" + escapeJson(lastTimestamp) + "\"}";
		}
	}

	private static class BmsEvent {
		String timestamp;
		int moduleId;
		int eventCode;
		String severity;
		String message;
		String rawLine;

		String toJson() {
			return "{\"timestamp\":\"" + escapeJson(timestamp) + "\"" +
				",\"moduleId\":" + moduleId +
				",\"eventCode\":" + eventCode +
				",\"severity\":\"" + escapeJson(severity) + "\"" +
				",\"message\":\"" + escapeJson(message) + "\"" +
				",\"rawLine\":\"" + escapeJson(rawLine) + "\"}";
		}
	}
}
