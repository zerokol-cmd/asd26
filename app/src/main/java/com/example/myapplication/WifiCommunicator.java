package com.example.myapplication;

import android.media.AudioTrack;
import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.util.Log;

import com.theeasiestway.opus.Opus;
import com.theeasiestway.opus.Constants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class WifiCommunicator {

    public interface ServerCallback {
        void onServerReady(String ipAndPort);
    }

    private static final String TAG = "WifiCommunicator";
    private static WifiCommunicator instance;

    private static final int MAX_DATAGRAM_SIZE = 65507;
    private static final byte[] OPUS_MAGIC = { (byte)'O', (byte)'P', (byte)'U', (byte)'S' };
    private static final int PCM_HEADER_SIZE = 16;
    private static final int OPUS_HEADER_SIZE = 11;
    private static final int PRE_BUFFER_PACKETS = 4;

    private DatagramSocket socket;
    String ip = "Not initialized";

    private volatile boolean isRunning = false;
    private final AtomicInteger sessionId = new AtomicInteger(0);
    private ExecutorService executor;
    private AudioTrack activeAudioTrack;
    private Opus activeDecoder;
    private byte[] activeHeader = null;

    private boolean isSameHeader(byte[] incoming) {
        if (activeHeader == null) return false;
        if (incoming.length != activeHeader.length) return false;
        return Arrays.equals(incoming, activeHeader);
    }

    private WifiCommunicator() { }

    public static synchronized WifiCommunicator getInstance() {
        if (instance == null) instance = new WifiCommunicator();
        return instance;
    }

    public void recreate() { recreate(null); }

    public void recreate(final ServerCallback callback) {
        Log.i(TAG, "recreate() called");
        stopServer();
        executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                socket = new DatagramSocket(0);
                ip = getLocalIpAddress() + ":" + socket.getLocalPort();
                isRunning = true;
                Log.i(TAG, "UDP socket bound to: " + ip);

                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onServerReady(ip);
                });

                byte[] buf = new byte[MAX_DATAGRAM_SIZE];
                DatagramPacket packet = new DatagramPacket(buf, buf.length);

                while (isRunning && !socket.isClosed()) {
                    socket.receive(packet);
                    byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

                    if (isOpusMagic(data) && data.length == OPUS_HEADER_SIZE) {
                        Log.i(TAG, "Opus header received");
                        handleOpus(data);
                    } else if (!isOpusMagic(data) && data.length == PCM_HEADER_SIZE) {
                        Log.i(TAG, "PCM header received");
                        handlePcm(data);
                    }
                    // Audio packets arriving before a header are silently dropped —
                    // C# resends the header every 500ms so recovery is automatic
                }

            } catch (Exception e) {
                if (e.getMessage() != null) {
                    ip = "Failed: " + e.getMessage();
                    Log.e(TAG, ip);
                }
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (callback != null) callback.onServerReady(ip);
                });
            }
        });
    }

    private void releaseActiveResources() {
        if (activeDecoder != null) {
            try { activeDecoder.decoderRelease(); } catch (Exception ignored) { }
            activeDecoder = null;
        }
        if (activeAudioTrack != null) {
            try {
                activeAudioTrack.pause();
                activeAudioTrack.flush();
                activeAudioTrack.stop();
                activeAudioTrack.release();
            } catch (Exception ignored) { }
            activeAudioTrack = null;
        }
    }

    private void handlePcm(byte[] headerData) throws IOException {
        final int mySession = sessionId.incrementAndGet();
        activeHeader = headerData;
        releaseActiveResources();

        ByteBuffer bb = ByteBuffer.wrap(headerData).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate    = bb.getInt();
        int channels      = bb.getInt();
        int bitsPerSample = bb.getInt();
        int audioFormat   = bb.getInt();

        Log.i(TAG, "PCM - SR:" + sampleRate + " Ch:" + channels + " Bits:" + bitsPerSample + " Fmt:" + audioFormat);

        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int encoding;
        if (audioFormat == 1) {
            encoding = (bitsPerSample == 16) ? AudioFormat.ENCODING_PCM_16BIT : AudioFormat.ENCODING_PCM_8BIT;
        } else if (audioFormat == 3) {
            encoding = AudioFormat.ENCODING_PCM_FLOAT;
        } else {
            Log.e(TAG, "Unsupported PCM format: " + audioFormat);
            return;
        }

        AudioTrack audioTrack = buildAudioTrack(sampleRate, channelConfig, encoding);
        activeAudioTrack = audioTrack;

        byte[] buf = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        byte[][] preBuffer = new byte[PRE_BUFFER_PACKETS][];
        for (int i = 0; i < PRE_BUFFER_PACKETS && isRunning && sessionId.get() == mySession; i++) {
            socket.receive(packet);
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            if (!isHeader(data)) preBuffer[i] = data;
            else { i--; }
        }

        audioTrack.play();
        for (byte[] chunk : preBuffer) {
            if (chunk == null || sessionId.get() != mySession) break;
            writePcm(audioTrack, chunk, encoding);
        }

        while (isRunning && !socket.isClosed() && sessionId.get() == mySession) {
            socket.receive(packet);
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

            if (isHeader(data)) {
                Log.i(TAG, "New header during PCM stream, switching");
                if (isOpusMagic(data) && data.length == OPUS_HEADER_SIZE) {
                    releaseActiveResources();
                    handleOpus(data);
                } else if (data.length == PCM_HEADER_SIZE) {
                    releaseActiveResources();
                    handlePcm(data);
                }
                return;
            }

            if (sessionId.get() != mySession) break;
            writePcm(audioTrack, data, encoding);
        }

        releaseActiveResources();
    }

    private void writePcm(AudioTrack audioTrack, byte[] data, int encoding) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            ByteBuffer floatBb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int floatCount = data.length / 4;
            float[] floatBuffer = new float[floatCount];
            for (int i = 0; i < floatCount; i++) floatBuffer[i] = floatBb.getFloat();
            int written = audioTrack.write(floatBuffer, 0, floatCount, AudioTrack.WRITE_BLOCKING);
            if (written < 0) Log.e(TAG, "PCM float write error: " + written);
        } else {
            int written = audioTrack.write(data, 0, data.length);
            if (written < 0) Log.e(TAG, "PCM byte write error: " + written);
        }
    }

    private void handleOpus(byte[] headerData) throws IOException {
        final int mySession = sessionId.incrementAndGet();
        activeHeader = headerData;
        releaseActiveResources();

        if (headerData.length < OPUS_HEADER_SIZE) {
            Log.e(TAG, "Opus header too short: " + headerData.length);
            return;
        }

        ByteBuffer hb = ByteBuffer.wrap(headerData, 4, 7).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate = hb.getInt();
        int channels   = hb.get() & 0xFF;
        int frameSize  = hb.getShort() & 0xFFFF;

        Log.i(TAG, "Opus - SR:" + sampleRate + " Ch:" + channels + " FrameSize:" + frameSize);

        Constants.SampleRate sampleRateConst;
        if      (sampleRate == 8000)  sampleRateConst = Constants.SampleRate.Companion._8000();
        else if (sampleRate == 12000) sampleRateConst = Constants.SampleRate.Companion._12000();
        else if (sampleRate == 16000) sampleRateConst = Constants.SampleRate.Companion._16000();
        else if (sampleRate == 24000) sampleRateConst = Constants.SampleRate.Companion._24000();
        else                          sampleRateConst = Constants.SampleRate.Companion._48000();

        Constants.Channels channelsConst = (channels == 1)
                ? Constants.Channels.Companion.mono()
                : Constants.Channels.Companion.stereo();

        Constants.FrameSize frameSizeConst;
        if      (frameSize == 120)  frameSizeConst = Constants.FrameSize.Companion._120();
        else if (frameSize == 240)  frameSizeConst = Constants.FrameSize.Companion._240();
        else if (frameSize == 480)  frameSizeConst = Constants.FrameSize.Companion._480();
        else if (frameSize == 960)  frameSizeConst = Constants.FrameSize.Companion._960();
        else if (frameSize == 1920) frameSizeConst = Constants.FrameSize.Companion._1920();
        else                        frameSizeConst = Constants.FrameSize.Companion._2880();

        Opus codec = new Opus();
        codec.decoderInit(sampleRateConst, channelsConst);
        activeDecoder = codec;

        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        AudioTrack audioTrack = buildAudioTrack(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        activeAudioTrack = audioTrack;

        byte[] buf = new byte[MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        byte[][] preBuffer = new byte[PRE_BUFFER_PACKETS][];
        int collected = 0;
        while (collected < PRE_BUFFER_PACKETS && isRunning && sessionId.get() == mySession) {
            socket.receive(packet);
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            if (isHeader(data)) continue;
            byte[] decoded = codec.decode(data, frameSizeConst);
            if (decoded != null && decoded.length > 0) preBuffer[collected++] = decoded;
        }

        audioTrack.play();

        for (byte[] frame : preBuffer) {
            if (frame == null || sessionId.get() != mySession) break;
            int written = audioTrack.write(frame, 0, frame.length);
            if (written < 0) Log.e(TAG, "Pre-buffer write error: " + written);
        }

        while (isRunning && !socket.isClosed() && sessionId.get() == mySession) {
            socket.receive(packet);
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());

            if (isHeader(data)) {
                if (isSameHeader(data)) continue; // duplicate resent header — ignore completely
                Log.i(TAG, "Different header received mid-stream, switching");
                activeHeader = data;
                releaseActiveResources();
                if (isOpusMagic(data) && data.length == OPUS_HEADER_SIZE) handleOpus(data);
                else if (data.length == PCM_HEADER_SIZE) handlePcm(data);
                return;
            }

            if (sessionId.get() != mySession) break;

            byte[] decoded = codec.decode(data, frameSizeConst);
            if (decoded == null || decoded.length == 0) {
                Log.e(TAG, "Opus decode failed, packet size: " + data.length);
                continue;
            }

            int written = audioTrack.write(decoded, 0, decoded.length);
            if (written < 0) Log.e(TAG, "AudioTrack write error: " + written);
        }

        releaseActiveResources();
    }

    private boolean isHeader(byte[] data) {
        return (isOpusMagic(data) && data.length == OPUS_HEADER_SIZE)
                || (!isOpusMagic(data) && data.length == PCM_HEADER_SIZE);
    }

    private AudioTrack buildAudioTrack(int sampleRate, int channelConfig, int encoding) {
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding);
        int bufferSize = Math.max(minBuf * 8, 65536);

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();

        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(encoding)
                .build();

        return new AudioTrack(attrs, fmt, bufferSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
    }

    private boolean isOpusMagic(byte[] bytes) {
        if (bytes.length < 4) return false;
        for (int i = 0; i < OPUS_MAGIC.length; i++)
            if (bytes[i] != OPUS_MAGIC[i]) return false;
        return true;
    }

    public void stopServer() {
        isRunning = false;
        sessionId.incrementAndGet();
        releaseActiveResources();
        if (socket != null && !socket.isClosed()) socket.close();
        if (executor != null) executor.shutdownNow();
    }

    public String getIpAndPort() { return ip; }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces)
                for (InetAddress addr : Collections.list(intf.getInetAddresses()))
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address && intf.getName().contains("wlan"))
                        return addr.getHostAddress();
            for (NetworkInterface intf : interfaces)
                for (InetAddress addr : Collections.list(intf.getInetAddresses()))
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address)
                        return addr.getHostAddress();
        } catch (Exception ex) {
            Log.e(TAG, "Error getting IP: " + ex.getMessage());
        }
        return "127.0.0.1";
    }
}