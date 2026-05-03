package com.piotrek.bmsmobileviewer;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final long REFRESH_INTERVAL_MS = 5000L;
    private static final int HISTORY_LIMIT = 80;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final DecimalFormat two = new DecimalFormat("0.00");
    private final DecimalFormat three = new DecimalFormat("0.000");

    private EditText baseUrlInput;
    private TextView statusText;
    private TextView lastUpdateText;
    private TextView moduleCardsText;
    private TextView cellsText;
    private TextView rawText;
    private Button refreshButton;
    private Button saveButton;
    private Button autoRefreshButton;
    private BmsChartView voltageChart;
    private BmsChartView currentChart;
    private BmsChartView socChart;
    private BmsChartView cellsChart;

    private boolean autoRefresh = true;
    private boolean refreshInProgress = false;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (autoRefresh) {
                refreshData(false);
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        baseUrlInput = findViewById(R.id.baseUrlInput);
        statusText = findViewById(R.id.statusText);
        lastUpdateText = findViewById(R.id.lastUpdateText);
        moduleCardsText = findViewById(R.id.moduleCardsText);
        cellsText = findViewById(R.id.cellsText);
        rawText = findViewById(R.id.rawText);
        refreshButton = findViewById(R.id.refreshButton);
        saveButton = findViewById(R.id.saveButton);
        autoRefreshButton = findViewById(R.id.autoRefreshButton);
        voltageChart = findViewById(R.id.voltageChart);
        currentChart = findViewById(R.id.currentChart);
        socChart = findViewById(R.id.socChart);
        cellsChart = findViewById(R.id.cellsChart);

        baseUrlInput.setText(loadBaseUrl());
        configureCharts();

        saveButton.setOnClickListener(view -> {
            String baseUrl = normalizedBaseUrl();
            saveBaseUrl(baseUrl);
            statusText.setText("Saved API URL: " + baseUrl);
            refreshData(true);
        });

        refreshButton.setOnClickListener(view -> refreshData(true));
        autoRefreshButton.setOnClickListener(view -> {
            autoRefresh = !autoRefresh;
            updateAutoRefreshButton();
            if (autoRefresh) {
                refreshHandler.removeCallbacks(refreshRunnable);
                refreshHandler.post(refreshRunnable);
            } else {
                refreshHandler.removeCallbacks(refreshRunnable);
            }
        });

        updateAutoRefreshButton();
        refreshData(true);
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshRunnable);
        worker.shutdownNow();
    }

    private void configureCharts() {
        voltageChart.setTitle("Voltage (V)");
        voltageChart.setLineColor(0xFF6EA8FE);
        currentChart.setTitle("Current (A)");
        currentChart.setLineColor(0xFFFFC857);
        socChart.setTitle("SOC (%)");
        socChart.setLineColor(0xFF75D99D);
        cellsChart.setTitle("Cell voltages (V)");
        cellsChart.setLineColor(0xFFB48CFF);
    }

    private void updateAutoRefreshButton() {
        autoRefreshButton.setText(autoRefresh ? "Auto: ON" : "Auto: OFF");
        autoRefreshButton.setAlpha(autoRefresh ? 1.0f : 0.65f);
    }

    private void refreshData(boolean manual) {
        if (refreshInProgress) {
            return;
        }
        refreshInProgress = true;
        final String baseUrl = normalizedBaseUrl();
        if (manual) {
            statusText.setText("Loading...");
        }

        worker.execute(() -> {
            try {
                String health = fetchText(baseUrl + "/api/health");
                String latest = fetchText(baseUrl + "/api/latest");
                JSONArray latestArray = new JSONArray(latest);
                JSONObject firstModule = latestArray.length() > 0 ? latestArray.optJSONObject(0) : null;
                int moduleId = firstModule == null ? 1 : firstModule.optInt("moduleId", 1);
                String history = fetchText(baseUrl + "/api/history?moduleId=" + moduleId + "&limit=" + HISTORY_LIMIT);

                JSONObject healthJson = new JSONObject(health);
                JSONArray historyArray = new JSONArray(history);

                runOnUiThread(() -> {
                    renderHealth(healthJson);
                    renderLatest(latestArray);
                    renderHistory(historyArray);
                    refreshInProgress = false;
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    statusText.setText("Failed: " + ex.getMessage());
                    lastUpdateText.setText("Check Wi-Fi, PC IP address and backend port 8090.");
                    refreshInProgress = false;
                });
            }
        });
    }

    private void renderHealth(JSONObject healthJson) {
        String dbState = healthJson.optBoolean("dbConnected") ? "DB OK" : "DB OFF";
        String modules = joinIntArray(healthJson.optJSONArray("modulesSeen"));
        statusText.setText("Service " + healthJson.optString("status", "?").toUpperCase(Locale.ROOT)
            + " | " + dbState + " | Modules: " + (modules.isEmpty() ? "-" : modules));
        lastUpdateText.setText("Last refresh: " + java.text.DateFormat.getTimeInstance().format(new java.util.Date()));
    }

    private void renderLatest(JSONArray latestArray) {
        if (latestArray.length() == 0) {
            moduleCardsText.setText("No telemetry yet.");
            cellsText.setText("No cell data yet.");
            rawText.setText("");
            return;
        }

        StringBuilder modules = new StringBuilder();
        StringBuilder cells = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < latestArray.length(); i++) {
            JSONObject item = latestArray.optJSONObject(i);
            if (item == null) {
                continue;
            }

            modules.append("Module ").append(item.optInt("moduleId")).append('\n');
            modules.append("Voltage: ").append(three.format(item.optDouble("voltageV"))).append(" V\n");
            modules.append("Current: ").append(three.format(item.optDouble("currentA"))).append(" A\n");
            modules.append("SOC: ").append(two.format(item.optDouble("socPercent"))).append(" %\n");
            modules.append("Status: ").append(item.optInt("statusCode")).append("\n\n");

            JSONArray cellArray = item.optJSONArray("cellMv");
            if (cellArray != null && cellArray.length() > 0) {
                cells.append("Module ").append(item.optInt("moduleId")).append(": ");
                for (int c = 0; c < cellArray.length(); c++) {
                    if (c > 0) {
                        cells.append(" / ");
                    }
                    cells.append("C").append(c + 1).append(" ").append(cellArray.optInt(c)).append(" mV");
                }
                cells.append('\n');
            }

            raw.append(item.optString("rawLine", "")).append('\n');
        }
        moduleCardsText.setText(modules.toString().trim());
        cellsText.setText(cells.length() == 0 ? "No cell data yet." : cells.toString().trim());
        rawText.setText(raw.toString().trim());
    }

    private void renderHistory(JSONArray historyArray) {
        List<Float> voltage = new ArrayList<>();
        List<Float> current = new ArrayList<>();
        List<Float> soc = new ArrayList<>();
        List<Float> cellAverage = new ArrayList<>();

        for (int i = historyArray.length() - 1; i >= 0; i--) {
            JSONObject item = historyArray.optJSONObject(i);
            if (item == null) {
                continue;
            }
            voltage.add((float) item.optDouble("voltageV"));
            current.add((float) item.optDouble("currentA"));
            soc.add((float) item.optDouble("socPercent"));
            JSONArray cells = item.optJSONArray("cellMv");
            if (cells != null && cells.length() > 0) {
                float sum = 0;
                for (int c = 0; c < cells.length(); c++) {
                    sum += cells.optInt(c) / 1000.0f;
                }
                cellAverage.add(sum / cells.length());
            }
        }

        voltageChart.setValues(voltage);
        currentChart.setValues(current);
        socChart.setValues(soc);
        cellsChart.setValues(cellAverage);
    }

    private String fetchText(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
            }
            return text.toString();
        } finally {
            connection.disconnect();
        }
    }

    private String normalizedBaseUrl() {
        String raw = baseUrlInput.getText() == null ? "" : baseUrlInput.getText().toString().trim();
        if (raw.endsWith("/")) {
            return raw.substring(0, raw.length() - 1);
        }
        return raw;
    }

    private String loadBaseUrl() {
        return getSharedPreferences("bms_mobile_viewer", Context.MODE_PRIVATE)
            .getString("base_url", "http://192.168.31.70:8090");
    }

    private void saveBaseUrl(String baseUrl) {
        getSharedPreferences("bms_mobile_viewer", Context.MODE_PRIVATE)
            .edit()
            .putString("base_url", baseUrl)
            .apply();
    }

    private String joinIntArray(JSONArray array) {
        if (array == null || array.length() == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(array.optInt(i));
        }
        return text.toString();
    }
}
