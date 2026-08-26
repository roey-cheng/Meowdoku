import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class WebServerTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = WebMain.createServer(
                new InetSocketAddress("127.0.0.1", 0));
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();

            assertStatus(client, "GET", baseUrl + "/health", 200);
            HttpResponse<String> home = send(client, "GET", baseUrl + "/");
            if (home.statusCode() != 200 || !home.body().contains("Meowdoku")) {
                throw new AssertionError("Home page did not load the Meowdoku UI");
            }
            if (!home.body().contains("id=\"setup-screen\"")
                    || !home.body().matches(
                            "(?s).*<section[^>]*id=\"game-screen\"[^>]*hidden[^>]*>.*")) {
                throw new AssertionError(
                        "Home page should open on setup with the game screen hidden");
            }
            if (!home.body().contains("id=\"show-rules\"")
                    || !home.body().contains("<dialog id=\"rules-dialog\"")) {
                throw new AssertionError("Setup should provide the game-rules dialog");
            }
            assertStatus(client, "GET", baseUrl + "/styles.css", 200);
            assertStatus(client, "GET", baseUrl + "/press-start-2p.ttf", 200);
            assertStatus(client, "GET", baseUrl + "/app.js", 200);
            assertStatus(client, "GET", baseUrl + "/private.txt", 404);
            assertStatus(client, "GET", baseUrl + "/api/game", 404);
            assertStatus(client, "POST", baseUrl + "/api/guess?row=0&column=0", 404);
            assertStatus(client, "POST", baseUrl + "/api/game?size=3", 400);
            assertStatus(client, "POST", baseUrl + "/api/game?size=10", 400);
            assertStatus(client, "POST", baseUrl + "/api/game?size=cat", 400);
            assertStatus(client, "POST", baseUrl + "/api/game?size=3&size=5", 400);
            assertStatusWithOrigin(client, baseUrl + "/api/game?size=5",
                    "https://example.com", 403);

            HttpResponse<String> newGame = send(client, "POST",
                    baseUrl + "/api/game?size=5");
            if (newGame.statusCode() != 200
                    || intField(newGame.body(), "size") != 5
                    || intField(newGame.body(), "catsFound") != 1
                    || newGame.body().contains("solution")) {
                throw new AssertionError("New-game response is invalid: " + newGame.body());
            }

            HttpResponse<String> state = send(client, "GET", baseUrl + "/api/game");
            if (state.statusCode() != 200 || !state.body().contains("\"board\":")) {
                throw new AssertionError("Current game state is missing its board");
            }

            assertStatus(client, "POST", baseUrl + "/api/guess?row=-1&column=0", 400);
            assertStatus(client, "POST", baseUrl + "/api/guess?row=0&column=5", 400);
            assertStatus(client, "POST", baseUrl + "/api/guess?row=0&row=1&column=2", 400);

            HttpResponse<String> guess = send(client, "POST",
                    baseUrl + "/api/guess?row=0&column=0");
            if (guess.statusCode() != 200
                    || intField(guess.body(), "guesses") != 1
                    || guess.body().contains("solution")) {
                throw new AssertionError("Guess response is invalid: " + guess.body());
            }

            HttpResponse<String> smallGame = send(client, "POST",
                    baseUrl + "/api/game?size=4");
            boolean complete = smallGame.body().contains("\"complete\":true");
            for (int row = 0; row < 4 && !complete; row++) {
                for (int column = 0; column < 4 && !complete; column++) {
                    HttpResponse<String> turn = send(client, "POST", baseUrl
                            + "/api/guess?row=" + row + "&column=" + column);
                    if (turn.statusCode() != 200) {
                        throw new AssertionError("A valid turn failed: " + turn.body());
                    }
                    complete = turn.body().contains("\"complete\":true");
                    if (complete && !turn.body().contains("\"catsFound\":4")) {
                        throw new AssertionError("Completed game did not find all cats");
                    }
                }
            }
            if (!complete) {
                throw new AssertionError("The HTTP game could not be completed");
            }

            System.out.println("WebServerTest passed");
        } finally {
            server.stop(0);
        }
    }

    private static void assertStatus(HttpClient client, String method,
                                     String url, int expected) throws Exception {
        HttpResponse<String> response = send(client, method, url);
        if (response.statusCode() != expected) {
            throw new AssertionError("Expected " + expected + " for " + url
                    + ", got " + response.statusCode() + ": " + response.body());
        }
    }

    private static HttpResponse<String> send(HttpClient client, String method,
                                             String url) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        if (method.equals("POST")) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.GET();
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertStatusWithOrigin(HttpClient client, String url,
                                               String origin, int expected) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Origin", origin)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expected) {
            throw new AssertionError("Expected " + expected + " for cross-origin request, got "
                    + response.statusCode() + ": " + response.body());
        }
    }

    private static int intField(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new AssertionError("Missing JSON field: " + field);
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }
}
