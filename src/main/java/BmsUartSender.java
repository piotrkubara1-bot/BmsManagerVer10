import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BmsUartSender {

    private static final Path NATIVE_TMP_DIR = prepareNativeTempDirectory();
    private static final int FRAME_TIMEOUT_MS = 1200;
    private static final int MIN_REAL_CELL_MV = 500;
    private static final int MAX_REAL_CELL_MV = 5000;

    private final String portName;
    private final int baudRate;
    private final int moduleId;
    private final String ingestUrl;
    private final int pollIntervalMs;
    private final float currentZeroDeadbandA;
    private final boolean simulatorMode;
    private final boolean requireInitialConnection;
    private final boolean debugFrames;
    private final Random simulatorRandom = new Random();
    private final SimulatedPack simulatedPack;

    private SerialPort port;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected = false;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public BmsUartSender(String[] args) {
        this.portName = resolvePortName(args);
        this.baudRate = Integer.parseInt(env("SERIAL_BAUD", "115200"));
        this.moduleId = Integer.parseInt(env("DEFAULT_MODULE_ID", "1"));
        this.ingestUrl = env("BMS_API_INGEST_URL", "http://127.0.0.1:8090/api/ingest");
        this.pollIntervalMs = Integer.parseInt(env("TINYBMS_POLL_INTERVAL_MS", "2000"));
        this.currentZeroDeadbandA = Float.parseFloat(env("BMS_CURRENT_ZERO_DEADBAND_A", "0.50"));
        this.simulatorMode = "SIMULATED".equalsIgnoreCase(portName);
        this.requireInitialConnection = hasFlag(args, "--require-open");
        this.debugFrames = "1".equals(env("TINYBMS_DEBUG_FRAMES", "0"));
        this.simulatedPack = new SimulatedPack(simulatorRandom, clamp(safeInt(env("BMS_SIM_CELL_COUNT", "4"), 4), 1, 32));
    }

    public void start() {
        System.out.println("[BmsUartSender] Starting sender...");
        System.out.println("Port: " + portName + " @ " + baudRate);
        System.out.println("Module ID: " + moduleId);
        System.out.println("Ingest URL: " + ingestUrl);
        System.out.println("Native temp dir: " + NATIVE_TMP_DIR.toAbsolutePath());
        if (simulatorMode) {
            System.out.println("[BmsUartSender] SIMULATED mode enabled.");
        }

        initPort();
        if (requireInitialConnection && !connected) {
            System.err.println("[BmsUartSender] Initial connection failed. Exiting because --require-open was used.");
            scheduler.shutdownNow();
            System.exit(2);
            return;
        }

        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, 10, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pollAndSend, 1000, pollIntervalMs, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            if (simulatorMode) {
                connected = true;
                return;
            }
            if (port == null || !port.isOpen() || !connected) {
                System.out.println("[BmsUartSender] Watchdog: Port closed or not connected, reconnecting...");
                resetConnectionState();
                initPort();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private static Path prepareNativeTempDirectory() {
        try {
            String base = System.getProperty("user.home");
            Path tempDir = Path.of(base, ".bmsmanager", "tmp");

            Files.createDirectories(tempDir);
            Files.createDirectories(tempDir.resolve("jSerialComm"));

            // System.setProperty("jSerialComm.library.path", tempDir.resolve("jSerialComm").toAbsolutePath().toString());
            // System.out.println("[BmsUartSender] Using library path: " + System.getProperty("jSerialComm.library.path"));
            return tempDir;
        } catch (Exception e) {
            System.err.println("[BmsUartSender] Failed to prepare writable temp directory: " + e.getMessage());
            System.err.println("[BmsUartSender] jSerialComm may still fail if the default temp directory is blocked.");
            return Path.of(System.getProperty("user.home"), ".bmsmanager", "tmp");
        }
    }

    private static void cleanupOldJSerialCommFiles(Path root) {
        try {
            if (!Files.exists(root)) {
                return;
            }

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    if (name.endsWith(".dll") || name.endsWith(".tmp") || name.endsWith(".lock")) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (Exception ignored) {
                            // File may be locked by another process; ignore and continue.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) {
                    if (!dir.equals(root)) {
                        try {
                            Files.deleteIfExists(dir);
                        } catch (Exception ignored) {
                            // Ignore directories that cannot be removed.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // Cleanup is best-effort only.
        }
    }

    private void initPort() {
        try {
            if (simulatorMode) {
                resetConnectionState();
                connected = true;
                System.out.println("[BmsUartSender] Simulation source ready");
                return;
            }
            if (port != null && port.isOpen()) {
                port.closePort();
            }

            port = SerialPort.getCommPort(portName);
            port.setComPortParameters(baudRate, 8, 1, 0);
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);

            if (port.openPort()) {
                in = port.getInputStream();
                out = port.getOutputStream();
                connected = true;
                System.out.println("[BmsUartSender] Port opened successfully");
            } else {
                resetConnectionState();
                System.err.println("[BmsUartSender] Failed to open port " + portName);
            }
        } catch (Throwable e) {
            resetConnectionState();
            System.err.println("[BmsUartSender] Error initializing port: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private void resetConnectionState() {
        connected = false;
        try {
            if (in != null) in.close();
            if (out != null) out.close();
        } catch (IOException ignored) {}
        in = null;
        out = null;
    }

    private void pollAndSend() {
        if (!connected) return;

        try {
            TinyBmsSnapshot snapshot = simulatorMode ? buildSimulatedSnapshot() : readSnapshot();
            if (snapshot != null) {
                String line = formatBmsLine(snapshot);
                sendToServer(line);
            }
        } catch (Exception e) {
            System.err.println("[BmsUartSender] Poll error: " + e.getMessage());
            resetConnectionState();
        }
    }

    private void sendHeartbeat() {
        sendToServer("HEARTBEAT," + moduleId);
    }

    private TinyBmsSnapshot buildSimulatedSnapshot() {
        return simulatedPack.nextSnapshot();
    }

    private TinyBmsSnapshot readSnapshot() throws Exception {
        Float voltage = readFloat(0x14);
        if (voltage == null) return null;

        Float currentRaw = readFloat(0x15);
        if (currentRaw == null) return null;
        float current = normalizeCurrent(currentRaw);

        Long socRaw = readUInt32(0x1A);
        if (socRaw == null) return null;

        Integer status = readUInt16(0x18);
        if (status == null) return null;

        List<Integer> cells = readCellVoltages();
        if (cells == null) return null;
        voltage = reconcileVoltageWithCells(voltage, cells);

        return new TinyBmsSnapshot(voltage, current, socRaw, status, cells);
    }

    private String formatBmsLine(TinyBmsSnapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("BMS,").append(moduleId).append(",");
        sb.append(String.format(Locale.US, "%.3f", s.voltageV)).append(",");
        sb.append(String.format(Locale.US, "%.3f", s.currentA)).append(",");
        sb.append(s.socRaw).append(",");
        sb.append(s.statusCode);

        for (int cellMv : s.cellsMv) {
            sb.append(",").append(cellMv);
        }
        return sb.toString();
    }

    private void sendToServer(String data) {
        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(ingestUrl);
            URL url = uri.toURL();

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(data.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                System.err.println("[BmsUartSender] Server returned " + code + " for: " + data);
            }
        } catch (Exception ex) {
            System.err.println("[BmsUartSender] HTTP error: " + ex.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // --- UART Helpers ---

    private synchronized byte[] sendRaw(byte[] data, int expectedLen) throws IOException {
        if (!connected || port == null || in == null || out == null) return null;

        drainInput();
        byte[] pkt = addCRC(data);
        out.write(pkt);
        out.flush();
        logFrame("TX", pkt);

        return readFixedFrame(data[1] & 0xFF, expectedLen, FRAME_TIMEOUT_MS);
    }

    private Float readFloat(int cmd) throws IOException {
        byte[] r = sendRaw(new byte[]{(byte) 0xAA, (byte) cmd}, 8);
        if (r == null || r[1] != (byte) cmd) return null;
        return ByteBuffer.wrap(r, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private Long readUInt32(int cmd) throws IOException {
        byte[] r = sendRaw(new byte[]{(byte) 0xAA, (byte) cmd}, 8);
        if (r == null || r[1] != (byte) cmd) return null;
        return (long) ByteBuffer.wrap(r, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private Integer readUInt16(int cmd) throws IOException {
        byte[] r = sendRaw(new byte[]{(byte) 0xAA, (byte) cmd}, 6);
        if (r == null || r[1] != (byte) cmd) return null;
        return ByteBuffer.wrap(r, 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    private synchronized List<Integer> readCellVoltages() throws IOException {
        if (out == null || in == null) return null;

        drainInput();
        byte[] cmd = addCRC(new byte[]{(byte) 0xAA, 0x1C});
        out.write(cmd);
        out.flush();
        logFrame("TX", cmd);

        byte[] header = readHeader(0x1C, FRAME_TIMEOUT_MS);
        if (header == null) return null;

        int payloadLen = header[2] & 0xFF;
        if (payloadLen <= 0 || (payloadLen % 2) != 0 || payloadLen > 64) {
            return null;
        }

        byte[] body = new byte[payloadLen + 2];
        if (!readFullyWithTimeout(body, 0, body.length, FRAME_TIMEOUT_MS)) return null;

        byte[] frame = new byte[3 + body.length];
        frame[0] = header[0];
        frame[1] = header[1];
        frame[2] = header[2];
        System.arraycopy(body, 0, frame, 3, body.length);
        logFrame("RX", frame);
        if (!hasValidCrc(frame)) {
            System.err.println("[BmsUartSender] Ignoring cell voltage frame with invalid CRC");
            return null;
        }

        int cellCount = payloadLen / 2;
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < cellCount; i++) {
            int v = ((body[i * 2 + 1] & 0xFF) << 8) | (body[i * 2] & 0xFF);
            int cellMv = normalizeCellMv(v);
            if (cellMv > 0) {
                cells.add(cellMv);
            }
        }
        return cells;
    }

    private byte[] readFixedFrame(int expectedCommand, int expectedLen, int timeoutMs) throws IOException {
        byte[] header = readHeader(expectedCommand, timeoutMs);
        if (header == null) {
            return null;
        }

        byte[] frame = new byte[expectedLen];
        frame[0] = header[0];
        frame[1] = header[1];
        int remaining = expectedLen - 2;
        if (!readFullyWithTimeout(frame, 2, remaining, timeoutMs)) {
            return null;
        }
        logFrame("RX", frame);
        if (!hasValidCrc(frame)) {
            System.err.println("[BmsUartSender] Ignoring command 0x" + String.format("%02X", expectedCommand) + " frame with invalid CRC");
            return null;
        }
        return frame;
    }

    private byte[] readHeader(int expectedCommand, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int first = readByteUntil(deadline);
            if (first < 0) {
                return null;
            }
            if (first != 0xAA) {
                continue;
            }

            int command = readByteUntil(deadline);
            if (command < 0) {
                return null;
            }
            if (command != expectedCommand) {
                continue;
            }

            if (expectedCommand == 0x1C) {
                int payloadLen = readByteUntil(deadline);
                if (payloadLen < 0) {
                    return null;
                }
                return new byte[] {(byte) 0xAA, (byte) command, (byte) payloadLen};
            }
            return new byte[] {(byte) 0xAA, (byte) command};
        }
        return null;
    }

    private int readByteUntil(long deadline) throws IOException {
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                return in.read() & 0xFF;
            }
            sleepBriefly();
        }
        return -1;
    }

    private boolean readFullyWithTimeout(byte[] target, int offset, int length, int timeoutMs) throws IOException {
        int total = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (total < length && System.currentTimeMillis() < deadline) {
            int available = in.available();
            if (available > 0) {
                int read = in.read(target, offset + total, Math.min(length - total, available));
                if (read > 0) {
                    total += read;
                }
            } else {
                sleepBriefly();
            }
        }
        return total == length;
    }

    private void drainInput() throws IOException {
        if (in == null) {
            return;
        }
        while (in.available() > 0) {
            in.read(new byte[Math.min(in.available(), 256)]);
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private float normalizeCurrent(float raw) {
        if (!Float.isFinite(raw)) {
            return 0.0f;
        }
        float amps = Math.abs(raw) > 200.0f ? raw / 1000.0f : raw;
        return Math.abs(amps) < currentZeroDeadbandA ? 0.0f : amps;
    }

    private float reconcileVoltageWithCells(float packVoltage, List<Integer> cells) {
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
        float cellSumVoltage = sumMv / 1000.0f;
        if (!Float.isFinite(packVoltage) || packVoltage <= 0.0f) {
            return cellSumVoltage;
        }
        return Math.abs(cellSumVoltage - packVoltage) >= 0.20f ? cellSumVoltage : packVoltage;
    }

    private int normalizeCellMv(int raw) {
        int mv = raw >= 10000 ? Math.round(raw / 10.0f) : raw;
        if (mv < MIN_REAL_CELL_MV || mv > MAX_REAL_CELL_MV) {
            return -1;
        }
        return mv;
    }

    private byte[] addCRC(byte[] d) {
        int crc = 0xFFFF;
        for (byte b : d) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
            }
        }

        byte[] o = Arrays.copyOf(d, d.length + 2);
        o[o.length - 2] = (byte) (crc & 0xFF);
        o[o.length - 1] = (byte) (crc >> 8);
        return o;
    }

    private boolean hasValidCrc(byte[] frame) {
        if (frame == null || frame.length < 4) {
            return false;
        }
        int expected = ((frame[frame.length - 1] & 0xFF) << 8) | (frame[frame.length - 2] & 0xFF);
        int actual = crc16(frame, frame.length - 2);
        return expected == actual;
    }

    private int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
            }
        }
        return crc & 0xFFFF;
    }

    private void logFrame(String direction, byte[] frame) {
        if (!debugFrames || frame == null) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : frame) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", value & 0xFF));
        }
        System.out.println("[BmsUartSender] " + direction + " " + builder);
    }

    private String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int safeInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String resolvePortName(String[] args) {
        String fallback = env("SERIAL_PORT", "COM5");
        if (args == null || args.length == 0) {
            return fallback;
        }

        for (String arg : args) {
            if (arg == null) {
                continue;
            }

            String trimmed = arg.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("--port=")) {
                String value = trimmed.substring("--port=".length()).trim();
                if (!value.isEmpty()) {
                    return normalizePort(value);
                }
            }

            if (!trimmed.startsWith("--")) {
                return normalizePort(trimmed);
            }
        }

        return normalizePort(fallback);
    }

    private boolean hasFlag(String[] args, String flag) {
        if (args == null || flag == null) {
            return false;
        }
        for (String arg : args) {
            if (flag.equalsIgnoreCase(arg == null ? "" : arg.trim())) {
                return true;
            }
        }
        return false;
    }

    private String normalizePort(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.equalsIgnoreCase("SIM") || trimmed.equalsIgnoreCase("SIMULATED")) {
            return "SIMULATED";
        }
        return trimmed;
    }

    public static void main(String[] args) {
        new BmsUartSender(args).start();
    }

    private static class TinyBmsSnapshot {
        final float voltageV;
        final float currentA;
        final long socRaw;
        final int statusCode;
        final List<Integer> cellsMv;

        TinyBmsSnapshot(float v, float a, long soc, int status, List<Integer> cells) {
            this.voltageV = v;
            this.currentA = a;
            this.socRaw = soc;
            this.statusCode = status;
            this.cellsMv = cells;
        }
    }

    private static final class SimulatedPack {
        private final Random random;
        private final int cellCount;
        private final double[] cellOffsetsMv;
        private double socPercent;
        private double averageCellMv;
        private double currentA;

        SimulatedPack(Random random, int cellCount) {
            this.random = random;
            this.cellCount = cellCount;
            this.socPercent = 55.0 + random.nextDouble() * 25.0;
            this.averageCellMv = 3650.0 + random.nextDouble() * 250.0;
            this.cellOffsetsMv = new double[cellCount];
            for (int i = 0; i < cellOffsetsMv.length; i++) {
                cellOffsetsMv[i] = -8.0 + random.nextDouble() * 16.0;
            }
        }

        TinyBmsSnapshot nextSnapshot() {
            updateState();
            List<Integer> cells = buildCells();
            int sumMv = 0;
            for (Integer cell : cells) {
                sumMv += cell;
            }

            float packVoltage = sumMv / 1000.0f;
            float current = (float) currentA;
            long socRaw = Math.round(socPercent * 1_000_000.0);
            int status = Math.abs(currentA) < 0.50 ? 0 : (currentA > 0.0 ? 0x33 : 0x31);
            return new TinyBmsSnapshot(packVoltage, current, socRaw, status, cells);
        }

        private void updateState() {
            double modeRoll = random.nextDouble();
            if (modeRoll < 0.70) {
                currentA = -0.20 + random.nextDouble() * 0.40;
            } else if (modeRoll < 0.88) {
                currentA = 0.8 + random.nextDouble() * 4.2;
            } else {
                currentA = -(0.6 + random.nextDouble() * 3.5);
            }

            socPercent = clamp(socPercent - (currentA * 0.004) + ((random.nextDouble() - 0.5) * 0.02), 5.0, 99.0);
            double targetCellMv = 3000.0 + (socPercent / 100.0) * 1150.0;
            averageCellMv += (targetCellMv - averageCellMv) * 0.03 + (random.nextDouble() - 0.5) * 2.0;
            averageCellMv = clamp(averageCellMv, 3000.0, 4200.0);

            for (int i = 0; i < cellOffsetsMv.length; i++) {
                cellOffsetsMv[i] = clamp(cellOffsetsMv[i] + (random.nextDouble() - 0.5) * 0.8, -18.0, 18.0);
            }
        }

        private List<Integer> buildCells() {
            List<Integer> cells = new ArrayList<>();
            for (int i = 0; i < cellCount; i++) {
                double sagMv = currentA > 0.0 ? currentA * 1.5 : currentA * 0.7;
                int cellMv = (int) Math.round(clamp(averageCellMv + cellOffsetsMv[i] - sagMv, 2800.0, 4250.0));
                cells.add(cellMv);
            }
            return cells;
        }
    }
}
