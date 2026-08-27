import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebServerSmokeTest {
    private static final int CLIENT_COUNT = 50;
    private static final int ROUNDS = 2;
    private static final int REQUEST_TIMEOUT_SECONDS = 15;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS);

    public static void main(String[] args) throws Exception {
        HttpServer server = WebMain.createServer(new java.net.InetSocketAddress("127.0.0.1", 0));
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient[] clients = new HttpClient[CLIENT_COUNT];
            for (int index = 0; index < CLIENT_COUNT; index++) {
                clients[index] = cookieClient();
            }

            int[] sizes = {4, 5, 6, 7, 8, 9};
            for (int round = 1; round <= ROUNDS; round++) {
                int finalRound = round;
                CountDownLatch gate = new CountDownLatch(1);
                ExecutorService executor = Executors.newFixedThreadPool(Math.min(20, CLIENT_COUNT));

                List<CompletableFuture<SmokeResult>> futures = new ArrayList<>();
                for (int index = 0; index < CLIENT_COUNT; index++) {
                    int userId = index;
                    HttpClient client = clients[index];
                    int size = sizes[(userId + finalRound) % sizes.length];

                    CompletableFuture<SmokeResult> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            return runClient(baseUrl, client, userId, size, gate);
                        } catch (Throwable error) {
                            throw new RuntimeException("User " + userId + " failed", error);
                        }
                    }, executor);
                    futures.add(future);
                }

                gate.countDown();

                for (CompletableFuture<SmokeResult> future : futures) {
                    SmokeResult result = future.join();
                    if (!result.ok) {
                        throw new IllegalStateException("Round " + round + " failed: " + result.error);
                    }
                }
                java.util.Set<String> cookieIds = new java.util.HashSet<>();
                for (int index = 0; index < CLIENT_COUNT; index++) {
                    SmokeResult result = futures.get(index).join();
                    if (!cookieIds.add(result.cookieId)) {
                        throw new IllegalStateException("Duplicate cookie detected in round " + round);
                    }
                }
                executor.shutdown();
                if (!executor.awaitTermination(REQUEST_TIMEOUT_SECONDS + 5L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Round " + round + " tasks timed out");
                }
            }

            System.out.println("WebServerSmokeTest passed");
        } finally {
            WebMain.stopServer(server);
        }
    }

    private static SmokeResult runClient(String baseUrl, HttpClient client, int userId, int size,
                                        CountDownLatch gate) throws Exception {
        gate.await();

        HttpResponse<String> created = send(client, "POST",
                baseUrl + "/api/game?size=" + size);
        if (created.statusCode() != 200) {
            return SmokeResult.fail("POST game returned " + created.statusCode());
        }
        if (!created.headers().firstValue("Set-Cookie").isPresent()) {
            return SmokeResult.fail("POST game response missing session cookie");
        }
        String cookieId = extractCookieId(created.headers().firstValue("Set-Cookie").orElse(""));
        if (cookieId.isBlank()) {
            return SmokeResult.fail("Could not extract session cookie id");
        }

        int sizeInBody = intField(created.body(), "size");
        if (sizeInBody != size) {
            return SmokeResult.fail("Expected size " + size + " got " + sizeInBody);
        }
        if (intField(created.body(), "guesses") != 0) {
            return SmokeResult.fail("Expected 0 guesses after create");
        }

        HttpResponse<String> restored = send(client, "GET", baseUrl + "/api/game");
        if (restored.statusCode() != 200) {
            return SmokeResult.fail("GET game after create returned " + restored.statusCode());
        }
        if (intField(restored.body(), "size") != size) {
            return SmokeResult.fail("Session size changed after restore");
        }

        int guessRow = 0;
        int guessColumn = ThreadLocalRandom.current().nextInt(0, size);
        HttpResponse<String> guessed = send(client, "POST",
                baseUrl + "/api/guess?row=" + guessRow + "&column=" + guessColumn);
        if (guessed.statusCode() != 200) {
            return SmokeResult.fail("POST guess returned " + guessed.statusCode());
        }
        if (intField(guessed.body(), "guesses") != 1) {
            return SmokeResult.fail("Expected 1 guess after one submit");
        }
        if (intField(guessed.body(), "size") != size) {
            return SmokeResult.fail("Session size changed after guess");
        }
        return SmokeResult.ok(cookieId);
    }

    private static HttpClient cookieClient() {
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(manager)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    private static HttpResponse<String> send(HttpClient client, String method, String url)
            throws Exception {
        HttpRequest request = request(method, url).timeout(REQUEST_TIMEOUT).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (!response.body().contains("{") && !response.body().isBlank()) {
            throw new RuntimeException("Unexpected non-json body: " + response.body());
        }
        return response;
    }

    private static HttpRequest.Builder request(String method, String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        return method.equals("POST")
                ? request.POST(HttpRequest.BodyPublishers.noBody())
                : request.GET();
    }

    private static int intField(String json, String field) {
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("Missing field: " + field);
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Integer.parseInt(json.substring(start, end));
    }

    private static final class SmokeResult {
        private final boolean ok;
        private final String error;
        private final String cookieId;

        private static SmokeResult ok(String cookieId) {
            return new SmokeResult(true, null, cookieId);
        }

        private static SmokeResult fail(String error) {
            return new SmokeResult(false, error, "");
        }

        private SmokeResult(boolean ok, String error, String cookieId) {
            this.ok = ok;
            this.error = error;
            this.cookieId = cookieId;
        }
    }

    private static String extractCookieId(String cookieHeader) {
        if (!cookieHeader.startsWith("meowdoku_session=")) {
            return "";
        }
        int semicolon = cookieHeader.indexOf(";");
        return semicolon < 0 ? cookieHeader.substring("meowdoku_session=".length())
                : cookieHeader.substring("meowdoku_session=".length(), semicolon);
    }
}
