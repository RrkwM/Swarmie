package dev.swarmie.adapter.transport;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.swarmie.adapter.AdapterSession;
import dev.swarmie.adapter.SwarmieAdapter;
import dev.swarmie.contract.CancelAck;
import dev.swarmie.contract.CommandSubmission;
import dev.swarmie.contract.EventBatch;
import dev.swarmie.contract.RobotId;
import dev.swarmie.contract.SubmitAck;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default-off, localhost-only JSONL development transport.
 *
 * <p>Socket I/O runs on dedicated threads. The server thread only offers event
 * batches to bounded per-connection queues.
 */
public final class LocalJsonTransport implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalJsonTransport.class);
    private static final int BACKLOG = 16;

    private final AdapterSession session;
    private final TransportConfig config;
    private final ServerSocket serverSocket;
    private final ExecutorService io = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "swarmie-jsonl-io");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final dev.swarmie.adapter.event.AdapterEventBus.Subscription subscription;

    private LocalJsonTransport(AdapterSession session, TransportConfig config, ServerSocket serverSocket) {
        this.session = session;
        this.config = config;
        this.serverSocket = serverSocket;
        this.subscription = session.subscribe(this::publishEvents);
    }

    public static LocalJsonTransport startIfConfigured(AdapterSession session) {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        TransportConfig config;
        try {
            config = TransportConfig.load(configDir, TransportCodec.GSON).orElse(null);
        }
        catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("Local JSONL transport configuration rejected: {}", exception.getMessage());
            return null;
        }
        if (config == null) {
            LOGGER.info("Local JSONL transport disabled (no enabled config/swarmie-transport.json)");
            return null;
        }
        try {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port()), BACKLOG);
            LocalJsonTransport transport = new LocalJsonTransport(session, config, server);
            transport.start();
            LOGGER.info("Local JSONL transport listening on 127.0.0.1:{} (maxConnections={})",
                    config.port(), config.maxConnections());
            return transport;
        }
        catch (IOException exception) {
            LOGGER.error("Could not start local JSONL transport", exception);
            return null;
        }
    }

    private void start() {
        Thread acceptThread = new Thread(this::acceptLoop, "swarmie-jsonl-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!this.closed.get()) {
            try {
                Socket socket = this.serverSocket.accept();
                if (this.connectionCount.get() >= this.config.maxConnections()) {
                    this.io.execute(() -> reject(socket));
                    continue;
                }
                this.connectionCount.incrementAndGet();
                Connection connection = new Connection(socket);
                this.connections.add(connection);
                this.io.execute(connection::readLoop);
                this.io.execute(connection::writeLoop);
            }
            catch (IOException exception) {
                if (!this.closed.get()) {
                    LOGGER.warn("JSONL accept failed: {}", exception.getMessage());
                }
                return;
            }
        }
    }

    private void reject(Socket socket) {
        try (socket) {
            socket.setSoTimeout(1000);
            OutputStream out = socket.getOutputStream();
            out.write((TransportCodec.error(null, "CONNECTION_LIMIT",
                    "too many local JSONL connections") + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        catch (IOException ignored) {
            // The peer may already be gone.
        }
    }

    private void publishEvents(EventBatch batch) {
        for (Connection connection : this.connections) {
            connection.offer(batch);
        }
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }
        this.subscription.close();
        try {
            this.serverSocket.close();
        }
        catch (IOException ignored) {
            // Already closed.
        }
        for (Connection connection : this.connections) {
            connection.close();
        }
        this.connections.clear();
        this.io.shutdownNow();
        try {
            this.io.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Local JSONL transport stopped");
    }

    private final class Connection implements AutoCloseable {
        private final Socket socket;
        private final BlockingQueue<Object> outbound;
        private final AtomicBoolean connectionClosed = new AtomicBoolean();

        private Connection(Socket socket) {
            this.socket = socket;
            this.outbound = new ArrayBlockingQueue<>(LocalJsonTransport.this.config.maxOutboundEvents());
            offer(TransportCodec.hello(LocalJsonTransport.this.session.serverEpoch()));
        }

        private void readLoop() {
            try {
                this.socket.setSoTimeout(0);
                InputStream input = this.socket.getInputStream();
                String line;
                while (!this.connectionClosed.get()
                        && (line = readLine(input, LocalJsonTransport.this.config.maxLineBytes())) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    handle(line);
                }
            }
            catch (LineTooLongException exception) {
                offer(TransportCodec.error(null, "LINE_TOO_LONG", "request line exceeded limit"));
            }
            catch (IOException ignored) {
                // Connection closed or reset.
            }
            finally {
                close();
            }
        }

        private void writeLoop() {
            try {
                OutputStream output = this.socket.getOutputStream();
                while (!this.connectionClosed.get()) {
                    Object next = this.outbound.poll(500, TimeUnit.MILLISECONDS);
                    if (next == null) {
                        continue;
                    }
                    String line = next instanceof EventBatch batch
                            ? TransportCodec.pushedEvents(batch)
                            : next.toString();
                    output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            catch (IOException ignored) {
                // Connection closed or reset.
            }
            finally {
                close();
            }
        }

        private void handle(String line) {
            String requestId = null;
            try {
                JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                requestId = TransportCodec.requestId(request);
                String op = request.has("op") ? request.get("op").getAsString() : "";
                switch (op) {
                    case "robots" -> offer(TransportCodec.robots(
                            requestId,
                            LocalJsonTransport.this.session.serverEpoch(),
                            LocalJsonTransport.this.session.snapshotRobots()));
                    case "events" -> offer(TransportCodec.events(requestId, readEvents(request)));
                    case "submit" -> offer(TransportCodec.ack(requestId, submit(request)));
                    case "cancel" -> offer(TransportCodec.cancelAck(requestId, cancel(request)));
                    case "ping" -> offer("{\"type\":\"pong\"}");
                    default -> offer(TransportCodec.error(requestId, "UNKNOWN_OP", "unknown op: " + op));
                }
            }
            catch (Exception exception) {
                offer(TransportCodec.error(requestId, "INVALID_REQUEST", exception.getMessage()));
            }
        }

        private EventBatch readEvents(JsonObject request) {
            long after = request.has("after") ? request.get("after").getAsLong() : 0L;
            int max = request.has("max") ? request.get("max").getAsInt() : 64;
            return LocalJsonTransport.this.session.readEvents(after, Math.max(1, Math.min(max, 256)));
        }

        private SubmitAck submit(JsonObject request) {
            CommandSubmission submission = TransportCodec.parseSubmit(request);
            return SwarmieAdapter.submit(submission);
        }

        private CancelAck cancel(JsonObject request) {
            RobotId robot = TransportCodec.parseRobot(request);
            String correlation = TransportCodec.correlation(request);
            return SwarmieAdapter.cancel(robot, correlation);
        }

        private void offer(Object message) {
            if (this.connectionClosed.get()) {
                return;
            }
            if (!this.outbound.offer(message)) {
                // The transport must not block the server thread. Close this
                // slow consumer asynchronously; its next poll can detect missed
                // events by epoch/sequence.
                LocalJsonTransport.this.io.execute(this::close);
            }
        }

        @Override
        public void close() {
            if (!this.connectionClosed.compareAndSet(false, true)) {
                return;
            }
            LocalJsonTransport.this.connections.remove(this);
            LocalJsonTransport.this.connectionCount.decrementAndGet();
            try {
                this.socket.close();
            }
            catch (IOException ignored) {
                // Already closed.
            }
        }
    }

    private static String readLine(InputStream input, int maxBytes) throws IOException, LineTooLongException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            if (value == '\r') {
                continue;
            }
            if (buffer.size() >= maxBytes) {
                throw new LineTooLongException();
            }
            buffer.write(value);
        }
        return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.UTF_8);
    }

    private static final class LineTooLongException extends Exception {
    }
}
