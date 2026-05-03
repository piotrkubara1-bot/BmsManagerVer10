import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Random;

public class BmsTestFeeder {
    public static void main(String[] args) {
        String mode = "single";
        int module = 1;
        int count = 120;
        int intervalMs = 1000;
        int cellCount = 4;
        String endpoint = "http://127.0.0.1:8090/api/ingest";

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                mode = arg.substring("--mode=".length()).trim().toLowerCase();
            } else if (arg.startsWith("--module=")) {
                module = safeInt(arg.substring("--module=".length()), 1);
            } else if (arg.startsWith("--count=")) {
                count = safeInt(arg.substring("--count=".length()), 120);
            } else if (arg.startsWith("--interval-ms=")) {
                intervalMs = safeInt(arg.substring("--interval-ms=".length()), 1000);
            } else if (arg.startsWith("--cells=")) {
                cellCount = safeInt(arg.substring("--cells=".length()), 4);
            } else if (arg.startsWith("--endpoint=")) {
                endpoint = arg.substring("--endpoint=".length()).trim();
            }
        }

        if (module < 1 || module > 4) {
            module = 1;
        }
        cellCount = clamp(cellCount, 1, 32);

        System.out.println("[BmsTestFeeder] mode=" + mode + " count=" + count + " intervalMs=" + intervalMs + " cells=" + cellCount + " endpoint=" + endpoint);
        Random random = new Random();
        SimulatedPack[] packs = new SimulatedPack[5];
        for (int mod = 1; mod < packs.length; mod++) {
            packs[mod] = new SimulatedPack(random, cellCount);
        }

        for (int i = 0; i < count; i++) {
            String payload;
            if ("single".equals(mode)) {
                payload = packs[module].nextLine(module);
            } else if ("multi".equals(mode) || "all".equals(mode)) {
                StringBuilder sb = new StringBuilder();
                for (int mod = 1; mod <= 4; mod++) {
                    if (mod > 1) {
                        sb.append('\n');
                    }
                    sb.append(packs[mod].nextLine(mod));
                }
                payload = sb.toString();
            } else {
                payload = packs[module].nextLine(module);
            }

            post(endpoint, payload);
            if (i % 15 == 0) {
                String eventLine = "EVENT," + module + "," + (100 + (i % 10)) + ",INFO,simulator tick " + Instant.now();
                post(endpoint, eventLine);
            }

            try {
                Thread.sleep(Math.max(50, intervalMs));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("[BmsTestFeeder] Completed.");
    }

    private static void post(String endpoint, String body) {
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create(endpoint);
            URL url = uri.toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } catch (Exception ex) {
            System.err.println("[BmsTestFeeder] POST failed: " + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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

        String nextLine(int module) {
            updateState();
            int[] cells = buildCells();
            int sumMv = 0;
            for (int cell : cells) {
                sumMv += cell;
            }

            double packVoltage = sumMv / 1000.0;
            long socRaw = Math.round(socPercent * 1_000_000.0);
            int status = Math.abs(currentA) < 0.50 ? 0 : (currentA > 0.0 ? 0x33 : 0x31);

            StringBuilder line = new StringBuilder();
            line.append(String.format(Locale.US, "BMS,%d,%.3f,%.3f,%d,%d", module, packVoltage, currentA, socRaw, status));
            for (int cell : cells) {
                line.append(',').append(cell);
            }
            return line.toString();
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

        private int[] buildCells() {
            int[] cells = new int[cellCount];
            for (int i = 0; i < cellCount; i++) {
                double sagMv = currentA > 0.0 ? currentA * 1.5 : currentA * 0.7;
                cells[i] = (int) Math.round(clamp(averageCellMv + cellOffsetsMv[i] - sagMv, 2800.0, 4250.0));
            }
            return cells;
        }
    }
}
