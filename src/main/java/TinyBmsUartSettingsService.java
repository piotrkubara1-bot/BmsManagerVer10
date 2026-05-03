import com.fazecast.jSerialComm.SerialPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TinyBmsUartSettingsService implements AutoCloseable {
    private static final byte RESET_COMMAND = 0x02;
    private static final byte OPTION_CLEAR_EVENTS = 0x01;
    private static final byte OPTION_CLEAR_STATISTICS = 0x02;
    private static final byte OPTION_RESET_BMS = 0x05;
    private static final Map<String, RegisterBinding> REGISTER_MAP = new LinkedHashMap<>();

    static {
        REGISTER_MAP.put("overvoltage_protection_v", new RegisterBinding(0x012C, 1000.0));
        REGISTER_MAP.put("undervoltage_protection_v", new RegisterBinding(0x012E, 1000.0));
        REGISTER_MAP.put("charge_overcurrent_a", new RegisterBinding(0x0134, 1.0));
        REGISTER_MAP.put("discharge_overcurrent_a", new RegisterBinding(0x0135, 1.0));
        REGISTER_MAP.put("discharge_temperature_high_c", new RegisterBinding(0x0139, 1.0));
        REGISTER_MAP.put("early_balancing_threshold_v", new RegisterBinding(0x013B, 1000.0));
    }

    private final SerialPort port;
    private final InputStream input;
    private final OutputStream output;
    private byte[] lastResponse = new byte[0];

    public TinyBmsUartSettingsService(String portName, int baudRate) throws IOException {
        if (portName == null || portName.trim().isEmpty()) {
            throw new IOException("Serial port is required.");
        }
        if ("SIMULATED".equalsIgnoreCase(portName.trim())) {
            throw new IOException("Maintenance commands are unavailable in SIMULATED mode.");
        }

        port = SerialPort.getCommPort(portName.trim());
        port.setComPortParameters(baudRate, 8, 1, 0);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1200, 0);

        if (!port.openPort()) {
            throw new IOException("Failed to open port " + portName.trim());
        }

        input = port.getInputStream();
        output = port.getOutputStream();
        drainInput();
    }

    public static boolean supportsKey(String key) {
        return REGISTER_MAP.containsKey(key);
    }

    public static String supportedKeysSummary() {
        return String.join(", ", REGISTER_MAP.keySet());
    }

    public void writeSetting(String key, double value) throws IOException {
        RegisterBinding binding = REGISTER_MAP.get(key);
        if (binding == null) {
            throw new IOException("Setting is not supported for UART write: " + key);
        }

        int encoded = (int) Math.round(value * binding.scaleFactor);
        byte[] command = new byte[] {
            (byte) 0xAA,
            0x0D,
            0x04,
            (byte) (binding.register & 0xFF),
            (byte) ((binding.register >> 8) & 0xFF),
            (byte) (encoded & 0xFF),
            (byte) ((encoded >> 8) & 0xFF)
        };

        sendWrite(command);
    }

    public void applySafeLiIonProfile() throws IOException {
        writeSetting("overvoltage_protection_v", 4.2);
        writeSetting("undervoltage_protection_v", 3.0);
    }

    public void resetBms() throws IOException {
        sendMaintenanceCommand(OPTION_RESET_BMS, true);
    }

    public void clearEvents() throws IOException {
        sendMaintenanceCommand(OPTION_CLEAR_EVENTS, false);
    }

    public void clearStatistics() throws IOException {
        sendMaintenanceCommand(OPTION_CLEAR_STATISTICS, false);
    }

    public List<TinyBmsEvent> readNewestEvents() throws IOException {
        return readEvents(0x11);
    }

    public List<TinyBmsEvent> readAllEvents() throws IOException {
        return readEvents(0x12);
    }

    public String lastResponseHex() {
        return toHex(lastResponse);
    }

    private void sendMaintenanceCommand(byte option, boolean allowNoAck) throws IOException {
        byte[] command = new byte[] {(byte) 0xAA, RESET_COMMAND, option};
        byte[] response = sendRequest(command, 5, 2000L, RESET_COMMAND);
        if (response == null || response.length < 3) {
            if (allowNoAck) {
                return;
            }
            throw new IOException("No response from TinyBMS.");
        }
        if (response[0] != (byte) 0xAA || response[1] != 0x01 || response[2] != RESET_COMMAND) {
            throw new IOException("Unexpected TinyBMS response: " + toHex(response));
        }
    }

    private void sendWrite(byte[] data) throws IOException {
        sendRequest(data, 128, 800L, -1);
    }

    private List<TinyBmsEvent> readEvents(int command) throws IOException {
        byte[] response = sendPayloadRequest(new byte[]{(byte) 0xAA, (byte) command}, command, 2048, 2000L);
        if (response == null || response.length < 9) {
            return new ArrayList<>();
        }

        int payloadLen = response[2] & 0xFF;
        if (payloadLen < 4) {
            return new ArrayList<>();
        }

        int payloadOffset = 3;
        long baseTimestamp = uint32Le(response, payloadOffset);
        int eventCount = (payloadLen - 4) / 4;
        List<TinyBmsEvent> result = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            int offset = payloadOffset + 4 + (i * 4);
            if (offset + 3 >= response.length - 2) {
                break;
            }
            long timestamp = uint24Le(response, offset);
            int eventCode = response[offset + 3] & 0xFF;
            result.add(new TinyBmsEvent(baseTimestamp, timestamp, eventCode));
        }
        return result;
    }

    private byte[] sendPayloadRequest(byte[] data, int expectedCommand, int maxResponseBytes, long timeoutMillis) throws IOException {
        lastResponse = new byte[0];
        drainInput();
        byte[] packet = addCrc(data);
        output.write(packet);
        output.flush();

        byte[] response = readPayloadFrame(expectedCommand, maxResponseBytes, timeoutMillis);
        lastResponse = response == null ? new byte[0] : response;
        return response;
    }

    private byte[] sendRequest(byte[] data, int maxResponseBytes, long timeoutMillis, int expectedCommand) throws IOException {
        lastResponse = new byte[0];
        drainInput();
        byte[] packet = addCrc(data);
        output.write(packet);
        output.flush();

        byte[] response;
        if (expectedCommand >= 0) {
            response = readFrame(expectedCommand, maxResponseBytes, timeoutMillis);
            lastResponse = response == null ? new byte[0] : response;
            return response;
        }

        long deadline = System.currentTimeMillis() + timeoutMillis;
        byte[] buffer = new byte[Math.max(maxResponseBytes, 16)];
        while (System.currentTimeMillis() < deadline) {
            int available = input.available();
            if (available <= 0) {
                sleepQuietly(20L);
                continue;
            }
            int read = input.read(buffer, 0, Math.min(buffer.length, available));
            if (read > 0) {
                response = Arrays.copyOf(buffer, read);
                lastResponse = response;
                return response;
            }
        }
        return null;
    }

    private byte[] readFrame(int expectedCommand, int maxResponseBytes, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            int first = readOne(deadline);
            if (first < 0) {
                return null;
            }
            if (first != 0xAA) {
                continue;
            }

            int lenOrType = readOne(deadline);
            if (lenOrType < 0) {
                return null;
            }
            int command = readOne(deadline);
            if (command < 0) {
                return null;
            }
            if (expectedCommand >= 0 && command != expectedCommand) {
                continue;
            }

            byte[] response = new byte[Math.max(5, Math.min(maxResponseBytes, 512))];
            response[0] = (byte) first;
            response[1] = (byte) lenOrType;
            response[2] = (byte) command;
            int offset = 3;
            while (offset < 5 && System.currentTimeMillis() < deadline) {
                int next = readOne(deadline);
                if (next < 0) {
                    break;
                }
                response[offset++] = (byte) next;
            }
            byte[] frame = Arrays.copyOf(response, offset);
            if (frame.length >= 5 && !hasValidCrc(frame)) {
                throw new IOException("Invalid TinyBMS response CRC: " + toHex(frame));
            }
            return frame;
        }
        return null;
    }

    private byte[] readPayloadFrame(int expectedCommand, int maxResponseBytes, long timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            int first = readOne(deadline);
            if (first < 0) {
                return null;
            }
            if (first != 0xAA) {
                continue;
            }

            int commandOrError = readOne(deadline);
            if (commandOrError < 0) {
                return null;
            }

            if (commandOrError == 0x00) {
                int command = readOne(deadline);
                int error = readOne(deadline);
                int crcL = readOne(deadline);
                int crcH = readOne(deadline);
                byte[] frame = new byte[] {
                    (byte) 0xAA, 0x00, (byte) command, (byte) error, (byte) crcL, (byte) crcH
                };
                if (!hasValidCrc(frame)) {
                    throw new IOException("Invalid TinyBMS error response CRC: " + toHex(frame));
                }
                throw new IOException("TinyBMS returned error " + error + " for command 0x" + String.format("%02X", command));
            }

            if (commandOrError != expectedCommand) {
                continue;
            }

            int payloadLen = readOne(deadline);
            if (payloadLen < 0) {
                return null;
            }
            int totalLength = 3 + payloadLen + 2;
            if (totalLength > maxResponseBytes) {
                throw new IOException("TinyBMS response too large: " + totalLength + " bytes.");
            }

            byte[] frame = new byte[totalLength];
            frame[0] = (byte) first;
            frame[1] = (byte) commandOrError;
            frame[2] = (byte) payloadLen;
            for (int i = 3; i < totalLength; i++) {
                int value = readOne(deadline);
                if (value < 0) {
                    return null;
                }
                frame[i] = (byte) value;
            }
            if (!hasValidCrc(frame)) {
                throw new IOException("Invalid TinyBMS payload response CRC: " + toHex(frame));
            }
            return frame;
        }
        return null;
    }

    private int readOne(long deadline) throws IOException {
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                return input.read() & 0xFF;
            }
            sleepQuietly(10L);
        }
        return -1;
    }

    private void drainInput() throws IOException {
        long deadline = System.currentTimeMillis() + 150L;
        byte[] scratch = new byte[128];
        while (System.currentTimeMillis() < deadline) {
            int available = input.available();
            if (available <= 0) {
                sleepQuietly(10L);
                continue;
            }
            input.read(scratch, 0, Math.min(scratch.length, available));
        }
    }

    private static void sleepQuietly(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("UART write interrupted.", ex);
        }
    }

    private static byte[] addCrc(byte[] data) {
        int crc = crc16(data, data.length);

        byte[] output = Arrays.copyOf(data, data.length + 2);
        output[output.length - 2] = (byte) (crc & 0xFF);
        output[output.length - 1] = (byte) ((crc >> 8) & 0xFF);
        return output;
    }

    private static boolean hasValidCrc(byte[] frame) {
        if (frame == null || frame.length < 4) {
            return false;
        }
        int expected = ((frame[frame.length - 1] & 0xFF) << 8) | (frame[frame.length - 2] & 0xFF);
        int actual = crc16(frame, frame.length - 2);
        return expected == actual;
    }

    private static int crc16(byte[] data, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= data[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xA001 : crc >> 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static String toHex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : data) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format("%02X", value & 0xFF));
        }
        return builder.toString();
    }

    private static long uint32Le(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF)
            | (((long) data[offset + 1] & 0xFF) << 8)
            | (((long) data[offset + 2] & 0xFF) << 16)
            | (((long) data[offset + 3] & 0xFF) << 24);
    }

    private static long uint24Le(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF)
            | (((long) data[offset + 1] & 0xFF) << 8)
            | (((long) data[offset + 2] & 0xFF) << 16);
    }

    @Override
    public void close() {
        try {
            input.close();
        } catch (Exception ignored) {
        }
        try {
            output.close();
        } catch (Exception ignored) {
        }
        try {
            if (port.isOpen()) {
                port.closePort();
            }
        } catch (Exception ignored) {
        }
    }

    private static final class RegisterBinding {
        final int register;
        final double scaleFactor;

        RegisterBinding(int register, double scaleFactor) {
            this.register = register;
            this.scaleFactor = scaleFactor;
        }
    }

    public static final class TinyBmsEvent {
        public final long baseTimestamp;
        public final long timestamp;
        public final int eventCode;

        TinyBmsEvent(long baseTimestamp, long timestamp, int eventCode) {
            this.baseTimestamp = baseTimestamp;
            this.timestamp = timestamp;
            this.eventCode = eventCode;
        }
    }
}
