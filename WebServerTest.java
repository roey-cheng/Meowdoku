import com.sun.net.httpserver.HttpServer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public class WebServerTest {
    private static final Map<HttpClient, CookieManager> COOKIE_MANAGERS
            = new IdentityHashMap<>();
    public static void main(String[] args) throws Exception {
        HttpServer server = WebMain.createServer(
                new InetSocketAddress("127.0.0.1", 0));
        assertBoundedExecutor(server);
        server.start();

        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient plainClient = HttpClient.newHttpClient();
            HttpClient clientA = cookieClient();
            HttpClient clientB = cookieClient();

            testMissingAndForgedSessions(baseUrl, plainClient);
            testStaticAndHealth(baseUrl, plainClient);
            testIndependentSessions(baseUrl, clientA, clientB);
            testConcurrentGuesses(baseUrl);
            testValidationAndReplacement(baseUrl, clientA);
            testLostGame(baseUrl, clientA);
            testOriginProtection(baseUrl, plainClient);
            testExpiryAndCapacity();
            testSecureCookieAndPublicOrigin();
            testUnexpectedFailureIsGeneric();

            System.out.println("WebServerTest passed");
        } finally {
            stopAndAssert(server);
        }
    }

    private static void testExpiryAndCapacity() throws Exception {
        MutableClock clock = new MutableClock();
        HttpServer server = WebMain.createServer(
                new InetSocketAddress("127.0.0.1", 0), 1, 1_000,
                clock, false, null);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient clientA = cookieClient();
            HttpClient clientB = cookieClient();

            assertGame(send(clientA, "POST", baseUrl + "/api/game?size=4"), 4, 0);
            HttpResponse<String> full = send(clientB, "POST",
                    baseUrl + "/api/game?size=4");
            assertStatus(full, 503);
            assertNoSessionCookie(full);
            assertJsonError(full, "Server is at session capacity");

            assertGame(send(clientA, "POST", baseUrl + "/api/game?size=5"), 5, 0);
            clock.advance(900);
            assertGame(send(clientA, "GET", baseUrl + "/api/game"), 5, 0);
            clock.advance(900);
            assertGame(send(clientA, "GET", baseUrl + "/api/game"), 5, 0);
            clock.advance(1_000);
            HttpResponse<String> expired = send(clientA, "GET", baseUrl + "/api/game");
            assertStatus(expired, 404);
            assertNoSessionCookie(expired);

            assertGame(send(clientB, "POST", baseUrl + "/api/game?size=4"), 4, 0);
            assertStatus(send(clientA, "GET", baseUrl + "/api/game"), 404);
        } finally {
            stopAndAssert(server);
        }
    }

    private static void testSecureCookieAndPublicOrigin() throws Exception {
        MutableClock clock = new MutableClock();
        HttpServer server = WebMain.createServer(
                new InetSocketAddress("127.0.0.1", 0), 5, 1_000,
                clock, true, "https://play.example");
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> exact = sendWithOrigin(client,
                    baseUrl + "/api/game?size=4", "https://play.example");
            assertGame(exact, 4, 0);
            assertCookieAttributes(exact, true);

            HttpResponse<String> mismatch = sendWithOrigin(client,
                    baseUrl + "/api/game?size=4", "https://PLAY.example");
            assertStatus(mismatch, 403);
            assertNoSessionCookie(mismatch);
        } finally {
            stopAndAssert(server);
        }
    }

    private static void testUnexpectedFailureIsGeneric() throws Exception {
        MutableClock clock = new MutableClock();
        HttpServer server = WebMain.createServer(
                new InetSocketAddress("127.0.0.1", 0), 5, 1_000,
                clock, false, null);
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = cookieClient();
            assertGame(send(client, "POST", baseUrl + "/api/game?size=4"), 4, 0);
            clock.fail = true;
            HttpResponse<String> failure = send(client, "GET", baseUrl + "/api/game");
            assertStatus(failure, 500);
            assertJsonError(failure, "Internal server error");
            assertNoSessionCookie(failure);
            if (failure.body().contains("IllegalStateException")
                    || failure.body().contains("clock failure")) {
                throw new AssertionError("Unexpected failures must not expose details");
            }
        } finally {
            stopAndAssert(server);
        }
    }

    private static void assertBoundedExecutor(HttpServer server) {
        if (!(server.getExecutor() instanceof ThreadPoolExecutor executor)
                || executor.getCorePoolSize() != 16
                || executor.getMaximumPoolSize() != 16
                || executor.getQueue().remainingCapacity() != 128) {
            throw new AssertionError("Server must use 16 workers and a 128-request queue");
        }
    }

    private static void stopAndAssert(HttpServer server) {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) server.getExecutor();
        WebMain.stopServer(server);
        if (!executor.isShutdown()) {
            throw new AssertionError("Server shutdown must stop its worker executor");
        }
    }

    private static void testStaticAndHealth(String baseUrl, HttpClient client)
            throws Exception {
        HttpResponse<String> health = send(client, "GET", baseUrl + "/health");
        assertStatus(health, 200);
        assertNoSessionCookie(health);
        assertSecurityHeaders(health);

        HttpResponse<String> headHealth = send(client, "HEAD", baseUrl + "/health");
        assertStatus(headHealth, 200);
        if (!headHealth.body().isEmpty()) {
            throw new AssertionError("HEAD /health must not return a response body");
        }
        assertNoSessionCookie(headHealth);
        assertSecurityHeaders(headHealth);

        HttpResponse<String> home = send(client, "GET", baseUrl + "/");
        if (home.statusCode() != 200 || !home.body().contains("Meowdoku")) {
            throw new AssertionError("Home page did not load the Meowdoku UI");
        }
        String courseCode = "<span>COMPSCI 230</span>";
        String courseName = "<small>Object Oriented Software Development</small>";
        String assignmentTitle = "<span>Assignment 1 Extended Version</span>";
        String meowdokuHeading = "<h1 id=\"setup-title\">Meowdoku</h1>";
        if (!home.body().contains("<title>Meowdoku CS230A1 Ruyi</title>")
                || home.body().indexOf(courseCode) < 0
                || home.body().indexOf(courseCode) > home.body().indexOf(courseName)
                || home.body().indexOf(courseName) > home.body().indexOf(assignmentTitle)
                || home.body().indexOf(assignmentTitle) > home.body().indexOf(meowdokuHeading)
                || home.body().contains("Choose your puzzle size")) {
            throw new AssertionError(
                    "Setup should show the assignment title above Meowdoku");
        }
        assertNoSessionCookie(home);
        assertSecurityHeaders(home);
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
        if (!home.body().contains("id=\"setup-message\"")) {
            throw new AssertionError("Setup should provide a visible error message area");
        }
        if (!home.body().contains("id=\"lives\"")
                || !home.body().contains("id=\"completion-title\"")) {
            throw new AssertionError("Game UI should show lives and an end-state title");
        }

        HttpResponse<String> headHome = send(client, "HEAD", baseUrl + "/");
        assertStatus(headHome, 200);
        if (!headHome.body().isEmpty()) {
            throw new AssertionError("HEAD / must not return a response body");
        }
        assertNoSessionCookie(headHome);
        assertSecurityHeaders(headHome);
        if (!home.body().contains(
                "id=\"lives\" class=\"lives\" role=\"status\" aria-live=\"polite\"")) {
            throw new AssertionError("Life changes should be announced to screen readers");
        }
        if (home.body().split(
                "<span class=\"pixel-heart\" aria-hidden=\"true\">\u2665</span>", -1
        ).length - 1 != 4) {
            throw new AssertionError("Life counter should use four pixel-font hearts");
        }

        for (String path : List.of("/styles.css", "/press-start-2p.ttf", "/app.js")) {
            HttpResponse<String> asset = send(client, "GET", baseUrl + path);
            assertStatus(asset, 200);
            assertNoSessionCookie(asset);
            assertSecurityHeaders(asset);
        }
        assertStatus(send(client, "GET", baseUrl + "/private.txt"), 404);
    }

    private static void testMissingAndForgedSessions(String baseUrl, HttpClient client)
            throws Exception {
        assertStatus(send(client, "GET", baseUrl + "/api/game"), 404);
        assertStatus(send(client, "POST",
                baseUrl + "/api/guess?row=0&column=0"), 404);

        HttpResponse<String> forgedGet = sendWithCookie(client, "GET",
                baseUrl + "/api/game", "meowdoku_session=forged");
        assertStatus(forgedGet, 404);
        HttpResponse<String> forgedGuess = sendWithCookie(client, "POST",
                baseUrl + "/api/guess?row=0&column=0",
                "meowdoku_session=forged");
        assertStatus(forgedGuess, 404);

        HttpResponse<String> forgedStart = sendWithCookie(client, "POST",
                baseUrl + "/api/game?size=4", "meowdoku_session=forged");
        assertGame(forgedStart, 4, 0);
        if (sessionCookie(forgedStart).equals("forged")) {
            throw new AssertionError("An unknown client cookie must not become a session ID");
        }
    }

    private static void testIndependentSessions(String baseUrl, HttpClient clientA,
                                                HttpClient clientB) throws Exception {
        HttpResponse<String> gameA = send(clientA, "POST", baseUrl + "/api/game?size=4");
        HttpResponse<String> gameB = send(clientB, "POST", baseUrl + "/api/game?size=9");
        assertGame(gameA, 4, 0);
        assertGame(gameB, 9, 0);

        String cookieA = sessionCookie(gameA);
        String cookieB = sessionCookie(gameB);
        if (cookieA.isBlank() || cookieB.isBlank() || cookieA.equals(cookieB)) {
            throw new AssertionError("Independent clients need different nonempty cookies");
        }
        if (!Pattern.matches("[A-Za-z0-9_-]{43}", cookieA)
                || !Pattern.matches("[A-Za-z0-9_-]{43}", cookieB)) {
            throw new AssertionError("Session IDs must encode 32 random bytes without padding");
        }
        assertCookieAttributes(gameA, false);
        assertCookieAttributes(gameB, false);
        if (COOKIE_MANAGERS.get(clientA).getCookieStore().getCookies().size() != 1
                || COOKIE_MANAGERS.get(clientB).getCookieStore().getCookies().size() != 1) {
            throw new AssertionError("JDK cookie jars did not retain the session cookies");
        }

        HttpResponse<String> guessA = send(clientA, "POST",
                baseUrl + "/api/guess?row=0&column=0");
        assertGame(guessA, 4, 1);
        assertCookieAttributes(guessA, false);

        assertGame(send(clientA, "GET", baseUrl + "/api/game"), 4, 1);
        assertGame(send(clientB, "GET", baseUrl + "/api/game"), 9, 0);

        assertGame(send(clientB, "POST", baseUrl + "/api/game?size=5"), 5, 0);
        assertGame(send(clientA, "GET", baseUrl + "/api/game"), 4, 1);
    }

    private static void testConcurrentGuesses(String baseUrl) throws Exception {
        HttpClient client = cookieClient();
        assertGame(send(client, "POST", baseUrl + "/api/game?size=9"), 9, 0);

        CompletableFuture<HttpResponse<String>> first = sendAsync(client, "POST",
                baseUrl + "/api/guess?row=0&column=0");
        CompletableFuture<HttpResponse<String>> second = sendAsync(client, "POST",
                baseUrl + "/api/guess?row=1&column=1");
        assertStatus(first.get(), 200);
        assertStatus(second.get(), 200);
        assertGame(send(client, "GET", baseUrl + "/api/game"), 9, 2);
    }

    private static void testValidationAndReplacement(String baseUrl, HttpClient client)
            throws Exception {
        for (String query : List.of("size=3", "size=10", "size=cat",
                "size=3&size=5")) {
            HttpResponse<String> invalid = send(client, "POST",
                    baseUrl + "/api/game?" + query);
            assertStatus(invalid, 400);
            assertNoSessionCookie(invalid);
        }
        assertGame(send(client, "GET", baseUrl + "/api/game"), 4, 1);

        for (String query : List.of("row=-1&column=0", "row=0&column=4",
                "row=0&row=1&column=2")) {
            HttpResponse<String> invalid = send(client, "POST",
                    baseUrl + "/api/guess?" + query);
            assertStatus(invalid, 400);
            assertNoSessionCookie(invalid);
        }
        assertGame(send(client, "GET", baseUrl + "/api/game"), 4, 1);
    }

    private static void testLostGame(String baseUrl, HttpClient client) throws Exception {
        HttpResponse<String> started = send(client, "POST", baseUrl + "/api/game?size=4");
        assertGame(started, 4, 0);
        int[] revealedCat = revealedCatPosition(started.body(), 4);
        HttpResponse<String> last = null;
        for (int column = 0; column < 4; column++) {
            if (column == revealedCat[1]) continue;
            last = send(client, "POST", baseUrl + "/api/guess?row=" + revealedCat[0]
                    + "&column=" + column);
            assertStatus(last, 200);
        }
        int otherRow = revealedCat[0] == 0 ? 1 : 0;
        last = send(client, "POST", baseUrl + "/api/guess?row=" + otherRow
                + "&column=" + revealedCat[1]);
        assertStatus(last, 200);
        if (last == null || intField(last.body(), "livesRemaining") != 0
                || !last.body().contains("\"lost\":true")
                || !last.body().contains("\"complete\":false")) {
            throw new AssertionError("Four wrong guesses should end the game: "
                    + (last == null ? "no response" : last.body()));
        }
        HttpResponse<String> finished = send(client, "POST",
                baseUrl + "/api/guess?row=1&column=0");
        assertStatus(finished, 409);
        assertNoSessionCookie(finished);
    }

    private static void testOriginProtection(String baseUrl, HttpClient client)
            throws Exception {
        HttpResponse<String> response = sendWithOrigin(client,
                baseUrl + "/api/game?size=5", "https://example.com");
        assertStatus(response, 403);
        assertNoSessionCookie(response);
    }

    private static HttpClient cookieClient() {
        CookieManager manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(manager).build();
        COOKIE_MANAGERS.put(client, manager);
        return client;
    }

    private static void assertGame(HttpResponse<String> response, int size, int guesses) {
        assertStatus(response, 200);
        if (intField(response.body(), "size") != size
                || intField(response.body(), "guesses") != guesses
                || intField(response.body(), "catsFound") < 1
                || intField(response.body(), "livesRemaining") < 0
                || response.body().contains("solution")
                || response.body().contains("meowdoku_session")) {
            throw new AssertionError("Game response is invalid: " + response.body());
        }
        if (!"no-store".equals(response.headers().firstValue("Cache-Control").orElse(""))) {
            throw new AssertionError("Game API responses must not be cached");
        }
        assertSecurityHeaders(response);
    }

    private static void assertCookieAttributes(HttpResponse<String> response,
                                               boolean secure) {
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow(
                () -> new AssertionError("Missing session cookie"));
        for (String attribute : List.of("Path=/", "HttpOnly", "SameSite=Lax",
                "Max-Age=28800")) {
            if (!cookie.contains(attribute)) {
                throw new AssertionError("Missing cookie attribute " + attribute + ": " + cookie);
            }
        }
        if (cookie.contains("Secure") != secure) {
            throw new AssertionError("Unexpected Secure cookie setting: " + cookie);
        }
    }

    private static String sessionCookie(HttpResponse<String> response) {
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow(
                () -> new AssertionError("Missing session cookie"));
        String name = "meowdoku_session=";
        if (!cookie.startsWith(name)) {
            throw new AssertionError("Unexpected session cookie: " + cookie);
        }
        return cookie.substring(name.length(), cookie.indexOf(';'));
    }

    private static void assertNoSessionCookie(HttpResponse<String> response) {
        if (response.headers().firstValue("Set-Cookie").isPresent()) {
            throw new AssertionError("Request must not create a session cookie");
        }
    }

    private static void assertJsonError(HttpResponse<String> response, String message) {
        if (!response.body().equals("{\"error\":\"" + message + "\"}")
                || !response.headers().firstValue("Content-Type").orElse("")
                        .equals("application/json; charset=utf-8")
                || !response.headers().firstValue("Cache-Control").orElse("")
                        .equals("no-store")) {
            throw new AssertionError("Invalid JSON error response: " + response.body());
        }
        assertSecurityHeaders(response);
    }

    private static void assertSecurityHeaders(HttpResponse<String> response) {
        String csp = response.headers().firstValue("Content-Security-Policy").orElse("");
        if (!csp.contains("default-src 'self'") || !csp.contains("frame-ancestors 'none'")) {
            throw new AssertionError("Missing restrictive Content-Security-Policy: " + csp);
        }
        if (!"nosniff".equals(response.headers()
                .firstValue("X-Content-Type-Options").orElse(""))) {
            throw new AssertionError("Missing X-Content-Type-Options");
        }
        if (!"no-referrer".equals(response.headers()
                .firstValue("Referrer-Policy").orElse(""))) {
            throw new AssertionError("Missing Referrer-Policy");
        }
        if (response.headers().firstValue("Permissions-Policy").orElse("").isBlank()) {
            throw new AssertionError("Missing Permissions-Policy");
        }
    }

    private static void assertStatus(HttpResponse<String> response, int expected) {
        if (response.statusCode() != expected) {
            throw new AssertionError("Expected " + expected + ", got "
                    + response.statusCode() + ": " + response.body());
        }
    }

    private static HttpResponse<String> send(HttpClient client, String method,
                                             String url) throws Exception {
        return client.send(request(method, url).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<HttpResponse<String>> sendAsync(
            HttpClient client, String method, String url) {
        return client.sendAsync(request(method, url).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendWithCookie(HttpClient client, String method,
                                                       String url, String cookie)
            throws Exception {
        return client.send(request(method, url).header("Cookie", cookie).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendWithOrigin(HttpClient client, String url,
                                                       String origin) throws Exception {
        return client.send(request("POST", url).header("Origin", origin).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String method, String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
        return request.method(method, HttpRequest.BodyPublishers.noBody());
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

    private static int[] revealedCatPosition(String json, int size) {
        var states = Pattern.compile("\\\"state\\\":\\\"([A-Z_]+)\\\"").matcher(json);
        int index = 0;
        while (states.find()) {
            if (states.group(1).equals("FOUND_CAT")) {
                return new int[]{index / size, index % size};
            }
            index++;
        }
        throw new AssertionError("Game JSON did not contain a revealed cat");
    }

    private static final class MutableClock implements LongSupplier {
        private long now;
        private boolean fail;

        @Override
        public long getAsLong() {
            if (fail) throw new IllegalStateException("clock failure");
            return now;
        }

        private void advance(long milliseconds) {
            now += milliseconds;
        }
    }
}
