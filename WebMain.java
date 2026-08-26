import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Serves the browser UI and connects HTTP guesses to the Java game. */
public class WebMain {
    public static void main(String[] args) throws IOException {
        String configuredPort = System.getenv("PORT");
        int port = configuredPort == null ? 8080 : Integer.parseInt(configuredPort);
        String host = configuredPort == null ? "127.0.0.1" : "0.0.0.0";
        HttpServer server = createServer(new InetSocketAddress(host, port));
        server.start();
        System.out.printf("Meowdoku is ready at http://localhost:%d%n", port);
    }

    static HttpServer createServer(InetSocketAddress address) throws IOException {
        HttpServer server = HttpServer.create(address, 0);
        ActiveGame activeGame = new ActiveGame();

        server.createContext("/health", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/health")) {
                sendJson(exchange, 404, errorJson("Not found"));
            } else if (!exchange.getRequestMethod().equals("GET")) {
                methodNotAllowed(exchange, "GET");
            } else {
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
            }
        });

        server.createContext("/api/game", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/api/game")) {
                sendJson(exchange, 404, errorJson("Not found"));
                return;
            }

            synchronized (activeGame) {
                if (exchange.getRequestMethod().equals("POST")) {
                    if (rejectCrossOrigin(exchange)) return;
                    createGame(exchange, activeGame);
                } else if (exchange.getRequestMethod().equals("GET")) {
                    if (activeGame.game == null) {
                        sendJson(exchange, 404, errorJson("No game has been started"));
                    } else {
                        sendJson(exchange, 200, gameJson(activeGame, "Game restored"));
                    }
                } else {
                    methodNotAllowed(exchange, "GET, POST");
                }
            }
        });

        server.createContext("/api/guess", exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/api/guess")) {
                sendJson(exchange, 404, errorJson("Not found"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            if (rejectCrossOrigin(exchange)) return;

            synchronized (activeGame) {
                submitGuess(exchange, activeGame);
            }
        });

        server.createContext("/", WebMain::serveStaticFile);
        return server;
    }

    private static void createGame(HttpExchange exchange, ActiveGame activeGame)
            throws IOException {
        try {
            int size = intParameter(exchange, "size");
            if (size < 4 || size > 9) {
                throw new IllegalArgumentException("Board size must be between 4 and 9");
            }

            activeGame.player = new BrowserPlayer("Web Player", size);
            activeGame.game = new MeowdokuGame(activeGame.player, size);
            activeGame.game.start();
            sendJson(exchange, 200,
                    gameJson(activeGame, "A starter cat has been revealed"));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, errorJson(error.getMessage()));
        }
    }

    private static void submitGuess(HttpExchange exchange, ActiveGame activeGame)
            throws IOException {
        if (activeGame.game == null) {
            sendJson(exchange, 404, errorJson("No game has been started"));
            return;
        }
        if (activeGame.game.isComplete()) {
            sendJson(exchange, 409, errorJson("The game is already complete"));
            return;
        }

        try {
            int row = intParameter(exchange, "row");
            int column = intParameter(exchange, "column");
            int size = activeGame.game.getBoard().getSize();
            if (row < 0 || row >= size || column < 0 || column >= size) {
                throw new IllegalArgumentException("Guess is outside the board");
            }

            activeGame.player.setNextGuess(new Position(row, column));
            GuessResult result = activeGame.game.playTurn();
            String message = activeGame.game.isComplete()
                    ? "All cats found!"
                    : result.getMessage();
            sendJson(exchange, 200, gameJson(activeGame, message));
        } catch (IllegalArgumentException error) {
            sendJson(exchange, 400, errorJson(error.getMessage()));
        }
    }

    private static String gameJson(ActiveGame activeGame, String message) {
        MeowdokuGame game = activeGame.game;
        GameBoard board = game.getBoard();
        Player player = game.getPlayer();
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append("\"size\":").append(board.getSize()).append(',')
                .append("\"score\":").append(player.getScore()).append(',')
                .append("\"guesses\":").append(player.getGuesses()).append(',')
                .append("\"catsFound\":").append(player.getCatsFound()).append(',')
                .append("\"complete\":").append(game.isComplete()).append(',')
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

    private static boolean rejectCrossOrigin(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return false;

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
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
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

    private static class ActiveGame {
        private BrowserPlayer player;
        private MeowdokuGame game;
    }
}
