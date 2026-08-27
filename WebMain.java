import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Serves the browser UI and connects HTTP guesses to the Java game. */
public class WebMain {
    private static final String SESSION_COOKIE = "meowdoku_session";
    private static final int SESSION_ID_BYTES = 32;
    private static final int COOKIE_MAX_AGE_SECONDS = 28800;
    private static final int DEFAULT_WORKER_THREADS = 16;
    private static final int DEFAULT_QUEUE_CAPACITY = 128;
    private static final int DEFAULT_MAX_SESSIONS = 500;
    private static final long DEFAULT_SESSION_MAX_AGE_MS = 8 * 60 * 60 * 1000L;
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();

    public static void main(String[] args) throws IOException {
        String configuredPort = System.getenv("PORT");
        int port = configuredPort == null ? 8080 : Integer.parseInt(configuredPort);
        String host = configuredPort == null ? "127.0.0.1" : "0.0.0.0";
        HttpServer server = createServer(new InetSocketAddress(host, port));
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> stopServer(server), "meowdoku-shutdown"));
        server.start();
        System.out.printf("Meowdoku is ready at http://localhost:%d%n", port);
    }

    static HttpServer createServer(InetSocketAddress address) throws IOException {
        String publicOrigin = System.getenv("PUBLIC_ORIGIN");
        if (publicOrigin != null && publicOrigin.isBlank()) publicOrigin = null;
        return createServer(address, DEFAULT_MAX_SESSIONS, DEFAULT_SESSION_MAX_AGE_MS,
                System::currentTimeMillis,
                "true".equalsIgnoreCase(System.getenv("SESSION_COOKIE_SECURE")),
                publicOrigin);
    }

    static HttpServer createServer(InetSocketAddress address, int maxSessions,
                                  long sessionMaxAgeMs, LongSupplier nowSupplier,
                                  boolean secureCookies, String publicOrigin) throws IOException {
        Objects.requireNonNull(nowSupplier, "nowSupplier");
        if (maxSessions < 1 || sessionMaxAgeMs < 1) {
            throw new IllegalArgumentException("Invalid server session configuration");
        }
        HttpServer server = HttpServer.create(address, 0);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                DEFAULT_WORKER_THREADS,
                DEFAULT_WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(DEFAULT_QUEUE_CAPACITY));
        server.setExecutor(executor);

        SessionManager sessionManager = new SessionManager(
                maxSessions, sessionMaxAgeMs, nowSupplier);

        server.createContext("/health", exchange -> handle(exchange, () -> {
            if (!exchange.getRequestURI().getPath().equals("/health")) {
                sendJson(exchange, 404, errorJson("Not found"));
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        }));

        server.createContext("/api/game", exchange -> handle(exchange, () -> {
            if (!exchange.getRequestURI().getPath().equals("/api/game")) {
                sendJson(exchange, 404, errorJson("Not found"));
                return;
            }
            String method = exchange.getRequestMethod();
            if (method.equals("POST")) {
                if (rejectCrossOrigin(exchange, publicOrigin)) return;
                createGame(exchange, sessionManager, secureCookies);
            } else if (method.equals("GET")) {
                getGame(exchange, sessionManager, secureCookies);
            } else {
                methodNotAllowed(exchange, "GET, POST");
            }
        }));

        server.createContext("/api/guess", exchange -> handle(exchange, () -> {
            if (!exchange.getRequestURI().getPath().equals("/api/guess")) {
                sendJson(exchange, 404, errorJson("Not found"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            if (rejectCrossOrigin(exchange, publicOrigin)) return;
            submitGuess(exchange, sessionManager, secureCookies);
        }));

        server.createContext("/", exchange ->
                handle(exchange, () -> serveStaticFile(exchange)));
        return server;
    }

    static void stopServer(HttpServer server) {
        if (server == null) return;
        server.stop(0);
        if (server.getExecutor() instanceof ThreadPoolExecutor executor) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException error) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void createGame(HttpExchange exchange, SessionManager sessionManager,
                                   boolean secureCookies) throws IOException {
        String incomingSessionId = parseSessionId(exchange);
        String message = "A starter cat has been revealed";
        try {
            GameSession existingSession = sessionManager.get(incomingSessionId);
            if (incomingSessionId != null && existingSession != null) {
                synchronized (existingSession) {
                    long now = sessionManager.now();
                    if (sessionManager.isActive(incomingSessionId, existingSession, now)) {
                        GameState newState = newGame(exchange);
                        existingSession.player = newState.player;
                        existingSession.game = newState.game;
                        existingSession.lastAccessMs = now;
                        exchange.getResponseHeaders().set("Set-Cookie",
                                sessionCookie(incomingSessionId, secureCookies));
                        sendJson(exchange, 200, gameJson(existingSession, message));
                        return;
                    }
                }
            }

            GameState state = newGame(exchange);
            long now = sessionManager.now();
            GameSession newSession = new GameSession(state.player, state.game, now);
            String newSessionId = sessionManager.add(newSession, now);
            if (newSessionId == null) {
                sendJson(exchange, 503, errorJson("Server is at session capacity"));
                return;
            }
            exchange.getResponseHeaders().set(
                    "Set-Cookie", sessionCookie(newSessionId, secureCookies));
            sendJson(exchange, 200, gameJson(newSession, message));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, errorJson(error.getMessage()));
        }
    }

    private static void getGame(HttpExchange exchange, SessionManager sessionManager,
                               boolean secureCookies) throws IOException {
        String sessionId = parseSessionId(exchange);
        if (sessionId == null) {
            sendJson(exchange, 404, errorJson("No game has been started"));
            return;
        }
        GameSession session = sessionManager.get(sessionId);
        if (session == null) {
            sendJson(exchange, 404, errorJson("No game has been started"));
            return;
        }

        synchronized (session) {
            long now = sessionManager.now();
            if (!sessionManager.isActive(sessionId, session, now)) {
                sendJson(exchange, 404, errorJson("No game has been started"));
                return;
            }
            session.lastAccessMs = now;
            exchange.getResponseHeaders().set(
                    "Set-Cookie", sessionCookie(sessionId, secureCookies));
            sendJson(exchange, 200, gameJson(session, "Session restored"));
        }
    }

    private static void submitGuess(HttpExchange exchange, SessionManager sessionManager,
                                   boolean secureCookies) throws IOException {
        String sessionId = parseSessionId(exchange);
        if (sessionId == null) {
            sendJson(exchange, 404, errorJson("No game has been started"));
            return;
        }

        GameSession session = sessionManager.get(sessionId);
        if (session == null) {
            sendJson(exchange, 404, errorJson("No game has been started"));
            return;
        }

        synchronized (session) {
            long now = sessionManager.now();
            if (!sessionManager.isActive(sessionId, session, now)) {
                sendJson(exchange, 404, errorJson("No game has been started"));
                return;
            }
            if (session.game.isOver()) {
                sendJson(exchange, 409, errorJson("The game is already over"));
                return;
            }

            try {
                int row = intParameter(exchange, "row");
                int column = intParameter(exchange, "column");
                session.player.setNextGuess(new Position(row, column));
                GuessResult result = session.game.playTurn();
                session.lastAccessMs = now;
                String message = session.game.isComplete() ? "All cats found!"
                        : session.game.isLost() ? "No hearts left!"
                        : result.getMessage();
                exchange.getResponseHeaders().set("Set-Cookie",
                        sessionCookie(sessionId, secureCookies));
                sendJson(exchange, 200, gameJson(session, message));
            } catch (IllegalArgumentException error) {
                sendJson(exchange, 400, errorJson(error.getMessage()));
            }
        }
    }

    private static GameState newGame(HttpExchange exchange) {
        int size = intParameter(exchange, "size");
        BrowserPlayer player = new BrowserPlayer("Web Player", size);
        MeowdokuGame game = new MeowdokuGame(player, size);
        game.start();
        return new GameState(player, game);
    }

    private static String gameJson(GameSession session, String message) {
        MeowdokuGame game = session.game;
        GameBoard board = game.getBoard();
        Player player = game.getPlayer();
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append("\"size\":").append(board.getSize()).append(',')
                .append("\"score\":").append(player.getScore()).append(',')
                .append("\"guesses\":").append(player.getGuesses()).append(',')
                .append("\"catsFound\":").append(player.getCatsFound()).append(',')
                .append("\"livesRemaining\":").append(player.getLivesRemaining()).append(',')
                .append("\"complete\":").append(game.isComplete()).append(',')
                .append("\"lost\":").append(game.isLost()).append(',')
                .append("\"message\":\"").append(jsonEscape(message)).append("\",")
                .append("\"board\":[");

        for (int row = 0; row < board.getSize(); row++) {
            if (row > 0) json.append(',');
            json.append('[');
            for (int column = 0; column < board.getSize(); column++) {
                if (column > 0) json.append(',');
                json.append("{\"regionId\":")
                        .append(board.getRegionId(row, column))
                        .append(",\"state\":\"")
                        .append(board.getCellState(row, column))
                        .append("\"}");
            }
            json.append(']');
        }
        return json.append("]}").toString();
    }

    private static String sessionCookie(String sessionId, boolean secureCookies) {
        return SESSION_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                + COOKIE_MAX_AGE_SECONDS + (secureCookies ? "; Secure" : "");
    }

    private static String parseSessionId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String cookie : header.split(";")) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && SESSION_COOKIE.equals(parts[0])) {
                String value = parts[1];
                if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private static String generateSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        SESSION_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static int intParameter(HttpExchange exchange, String name) {
        String value = null;
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                if (!key.equals(name)) continue;
                if (value != null) {
                    throw new IllegalArgumentException(name + " must be provided once");
                }
                value = parts.length == 2
                        ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                        : "";
            }
        }

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing parameter: " + name);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be one whole number");
        }
    }

    private static boolean rejectCrossOrigin(HttpExchange exchange, String publicOrigin) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return false;

        if (publicOrigin != null) {
            if (!origin.equals(publicOrigin)) {
                sendJson(exchange, 403, errorJson("Cross-origin requests are not allowed"));
                return true;
            }
            return false;
        }

        String host = exchange.getRequestHeaders().getFirst("Host");
        try {
            URI originUri = URI.create(origin);
            if (host != null && host.equalsIgnoreCase(originUri.getRawAuthority())) {
                return false;
            }
        } catch (IllegalArgumentException ignored) {
            // A malformed Origin is not a valid same-origin request.
        }

        sendJson(exchange, 403, errorJson("Cross-origin requests are not allowed"));
        return true;
    }

    private static void serveStaticFile(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }

        String requestPath = exchange.getRequestURI().getPath();
        String fileName;
        String contentType;
        if (requestPath.equals("/")) {
            fileName = "index.html";
            contentType = "text/html; charset=utf-8";
        } else if (requestPath.equals("/styles.css")) {
            fileName = "styles.css";
            contentType = "text/css; charset=utf-8";
        } else if (requestPath.equals("/press-start-2p.ttf")) {
            fileName = "fonts/PressStart2P-Regular.ttf";
            contentType = "font/ttf";
        } else if (requestPath.equals("/app.js")) {
            fileName = "app.js";
            contentType = "text/javascript; charset=utf-8";
        } else {
            sendJson(exchange, 404, errorJson("Not found"));
            return;
        }

        Path file = Path.of("web", fileName);
        if (!Files.isRegularFile(file)) {
            sendJson(exchange, 404, errorJson("Web asset is missing"));
            return;
        }
        byte[] body = Files.readAllBytes(file);
        addCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void methodNotAllowed(HttpExchange exchange, String allowed)
            throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        sendJson(exchange, 405, errorJson("Method not allowed"));
    }

    private static void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        addCommonHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void addCommonHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Permissions-Policy",
                "accelerometer=(), camera=(), geolocation=(), gyroscope=(), microphone=(), "
                        + "payment=(), usb=()");
    }

    private static void handle(HttpExchange exchange, ExchangeHandler handler) {
        try {
            handler.run();
        } catch (RuntimeException | IOException error) {
            System.err.printf("%s %s 500 %s%n", exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), error.getClass().getSimpleName());
            try {
                if (exchange.getResponseCode() == -1) {
                    sendJson(exchange, 500, errorJson("Internal server error"));
                } else {
                    exchange.close();
                }
            } catch (IOException ignored) {
                // Best effort response when request handling failed.
            }
        }
    }

    private static String errorJson(String message) {
        return "{\"error\":\"" + jsonEscape(message) + "\"}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static class SessionManager {
        private final int maxSessions;
        private final long sessionMaxAgeMs;
        private final LongSupplier nowSupplier;
        private final ConcurrentHashMap<String, GameSession> sessions
                = new ConcurrentHashMap<>();

        private SessionManager(int maxSessions, long sessionMaxAgeMs, LongSupplier nowSupplier) {
            this.maxSessions = maxSessions;
            this.sessionMaxAgeMs = sessionMaxAgeMs;
            this.nowSupplier = nowSupplier;
        }

        private void clearExpired(long now) {
            for (Map.Entry<String, GameSession> entry : sessions.entrySet()) {
                GameSession session = entry.getValue();
                synchronized (session) {
                    if (now - session.lastAccessMs >= sessionMaxAgeMs) {
                        sessions.remove(entry.getKey(), session);
                    }
                }
            }
        }

        private GameSession get(String sessionId) {
            return sessionId == null ? null : sessions.get(sessionId);
        }

        private boolean isActive(String sessionId, GameSession session, long now) {
            if (sessions.get(sessionId) != session) return false;
            if (now - session.lastAccessMs >= sessionMaxAgeMs) {
                sessions.remove(sessionId, session);
                return false;
            }
            return true;
        }

        private String add(GameSession session, long now) {
            synchronized (sessions) {
                clearExpired(now);
                if (sessions.size() >= maxSessions) return null;
                String sessionId;
                do {
                    sessionId = generateSessionId();
                } while (sessions.putIfAbsent(sessionId, session) != null);
                return sessionId;
            }
        }

        private long now() {
            return nowSupplier.getAsLong();
        }
    }

    private static class GameSession {
        private BrowserPlayer player;
        private MeowdokuGame game;
        private long lastAccessMs;

        private GameSession(BrowserPlayer player, MeowdokuGame game, long now) {
            this.player = player;
            this.game = game;
            this.lastAccessMs = now;
        }
    }

    private static class GameState {
        private final BrowserPlayer player;
        private final MeowdokuGame game;

        private GameState(BrowserPlayer player, MeowdokuGame game) {
            this.player = player;
            this.game = game;
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void run() throws IOException;
    }
}
