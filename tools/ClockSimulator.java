import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plays the part of a fleet of time clocks against a running attendance-gateway,
 * with the habits that make real clocks hard to serve: batches sent twice,
 * punches out of order, and every clock talking at once.
 *
 * <p>No build needed, it has no dependencies:
 *
 * <pre>
 * java tools/ClockSimulator.java --devices 20 --punches-per-device 5000
 * </pre>
 *
 * <p>It checks one thing and exits non-zero if it does not hold: the service
 * accepted every distinct punch exactly once, no matter how often it was sent.
 */
public class ClockSimulator {

    private static final Pattern FIELD = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"[^\"]*\"|-?\\d+)");

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String url = options.getOrDefault("url", "http://localhost:8080");
        String adminKey = options.getOrDefault("admin-key", "dev-admin-key");
        int devices = Integer.parseInt(options.getOrDefault("devices", "20"));
        int employees = Integer.parseInt(options.getOrDefault("employees-per-device", "50"));
        int punchesPerDevice = Integer.parseInt(options.getOrDefault("punches-per-device", "5000"));
        int batchSize = Integer.parseInt(options.getOrDefault("batch", "100"));
        double resendRate = Double.parseDouble(options.getOrDefault("resend-rate", "0.2"));
        long seed = Long.parseLong(options.getOrDefault("seed", "42"));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String run = Long.toString(System.currentTimeMillis(), 36);

        System.out.printf("Target %s: %d clocks x %d punches, batches of %d, %.0f%% of batches sent twice%n",
                url, devices, punchesPerDevice, batchSize, resendRate * 100);

        List<Clock> clocks = new ArrayList<>();
        for (int d = 0; d < devices; d++) {
            String site = "SIM-" + run + "-" + d;
            String body = post(http, url + "/api/v1/devices", "X-Admin-Key", adminKey,
                    "{\"name\":\"Simulated clock " + d + "\",\"siteCode\":\"" + site + "\",\"timeZone\":\"UTC\"}");
            Map<String, String> fields = fields(body);
            for (int e = 0; e < employees; e++) {
                send(http, HttpRequest.newBuilder(URI.create(url + "/api/v1/sites/" + site + "/mappings/" + e))
                        .header("X-Admin-Key", adminKey).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"employeeId\":\"" + site + "-E" + e + "\"}")));
            }
            clocks.add(new Clock(fields.get("id"), fields.get("apiKey")));
        }
        System.out.printf("Registered %d clocks and %d employee mappings%n", devices, devices * employees);

        double summarizedBefore = summarizedEvents(http, url);
        AtomicLong accepted = new AtomicLong();
        AtomicLong duplicates = new AtomicLong();
        AtomicLong quarantined = new AtomicLong();
        AtomicLong requests = new AtomicLong();
        List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());

        // A month back, so nothing is dated in the future, one minute apart per clock.
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC).minusDays(30).withSecond(0).withNano(0);
        List<Callable<Void>> work = new ArrayList<>();
        for (int d = 0; d < devices; d++) {
            Clock clock = clocks.get(d);
            Random random = new Random(seed + d);
            work.add(() -> {
                List<String> punches = new ArrayList<>(punchesPerDevice);
                for (int i = 0; i < punchesPerDevice; i++) {
                    punches.add("{\"userCode\":\"" + (i % employees) + "\",\"punchedAt\":\""
                            + start.plusMinutes(i) + ":00\",\"type\":\"" + (i / employees % 2 == 0 ? "IN" : "OUT") + "\"}");
                }
                for (int from = 0; from < punches.size(); from += batchSize) {
                    List<String> batch = new ArrayList<>(punches.subList(from, Math.min(from + batchSize, punches.size())));
                    // A clock that buffered while offline does not replay in order.
                    Collections.shuffle(batch, random);
                    String body = "{\"punches\":[" + String.join(",", batch) + "]}";
                    int sends = random.nextDouble() < resendRate ? 2 : 1;
                    for (int s = 0; s < sends; s++) {
                        long before = System.nanoTime();
                        String response = post(http, url + "/api/v1/devices/" + clock.id + "/punches",
                                "X-Device-Key", clock.apiKey, body);
                        latenciesMicros.add((System.nanoTime() - before) / 1_000);
                        requests.incrementAndGet();
                        Map<String, String> result = fields(response);
                        accepted.addAndGet(Long.parseLong(result.get("accepted")));
                        duplicates.addAndGet(Long.parseLong(result.get("duplicates")));
                        quarantined.addAndGet(Long.parseLong(result.get("quarantined")));
                    }
                }
                return null;
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(devices);
        long began = System.nanoTime();
        for (Future<Void> future : pool.invokeAll(work)) {
            future.get();
        }
        double seconds = (System.nanoTime() - began) / 1e9;
        pool.shutdown();

        long distinct = (long) devices * punchesPerDevice;
        long sent = accepted.get() + duplicates.get() + quarantined.get();
        long[] sorted = latenciesMicros.stream().mapToLong(Long::longValue).sorted().toArray();

        System.out.println();
        System.out.printf("Ingest finished in %.1f s%n", seconds);
        System.out.printf("  requests           %d (%.0f per second)%n", requests.get(), requests.get() / seconds);
        System.out.printf("  punches sent       %d (%.0f per second)%n", sent, sent / seconds);
        System.out.printf("  distinct punches   %d (%.0f per second)%n", distinct, distinct / seconds);
        System.out.printf("  accepted           %d%n", accepted.get());
        System.out.printf("  duplicates dropped %d%n", duplicates.get());
        System.out.printf("  quarantined        %d%n", quarantined.get());
        System.out.printf("  request latency    p50 %.1f ms, p95 %.1f ms, p99 %.1f ms, max %.1f ms%n",
                percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99), sorted[sorted.length - 1] / 1000.0);

        long drainBegan = System.nanoTime();
        double pending = pendingEvents(http, url);
        while (pending > 0 && (System.nanoTime() - drainBegan) / 1e9 < 300) {
            Thread.sleep(250);
            pending = pendingEvents(http, url);
        }
        double published = (System.nanoTime() - began) / 1e9;
        System.out.printf("Outbox empty %.1f s after the last request: %d events published in %.1f s (%.0f per second)%n",
                (System.nanoTime() - drainBegan) / 1e9, distinct, published, distinct / published);

        double summarized = summarizedEvents(http, url) - summarizedBefore;
        while (summarized < distinct && (System.nanoTime() - drainBegan) / 1e9 < 600) {
            Thread.sleep(250);
            summarized = summarizedEvents(http, url) - summarizedBefore;
        }
        double endToEnd = (System.nanoTime() - began) / 1e9;
        System.out.printf("Summaries caught up %.1f s after the last request: %d events consumed in %.1f s end to end (%.0f per second)%n",
                (System.nanoTime() - drainBegan) / 1e9, (long) summarized, endToEnd, distinct / endToEnd);

        boolean exactlyOnce = accepted.get() == distinct && quarantined.get() == 0 && pending == 0
                && summarized == distinct;
        System.out.println(exactlyOnce
                ? "OK: every distinct punch was accepted exactly once"
                : "FAILED: accepted " + accepted.get() + " of " + distinct + " distinct punches, "
                        + quarantined.get() + " quarantined, " + (long) pending + " events still pending, "
                        + (long) summarized + " events summarized");
        System.exit(exactlyOnce ? 0 : 1);
    }

    private record Clock(String id, String apiKey) {
    }

    private static double pendingEvents(HttpClient http, String url) throws Exception {
        return metric(http, url, "attendance_outbox_pending", "");
    }

    /** Events the daily summary consumer has applied for the first time. */
    private static double summarizedEvents(HttpClient http, String url) throws Exception {
        return metric(http, url, "attendance_summary_events_total", "result=\"applied\"");
    }

    private static double metric(HttpClient http, String url, String name, String label) throws Exception {
        String scrape = send(http, HttpRequest.newBuilder(URI.create(url + "/actuator/prometheus")).GET());
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(name) + "(?:\\{[^}]*" + Pattern.quote(label)
                + "[^}]*})?\\s+(\\S+)$").matcher(scrape);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
    }

    private static String post(HttpClient http, String url, String keyHeader, String key, String body) throws Exception {
        return send(http, HttpRequest.newBuilder(URI.create(url))
                .header(keyHeader, key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String send(HttpClient http, HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(response.request().method() + " " + response.uri() + " answered "
                    + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /** Enough JSON reading for flat responses of strings and integers. */
    private static Map<String, String> fields(String json) {
        Map<String, String> fields = new HashMap<>();
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) {
            String value = matcher.group(2);
            fields.put(matcher.group(1), value.startsWith("\"") ? value.substring(1, value.length() - 1) : value);
        }
        return fields;
    }

    private static double percentile(long[] sortedMicros, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sortedMicros.length) - 1;
        return sortedMicros[Math.max(0, index)] / 1000.0;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Expected --option value pairs, got: " + Arrays.toString(args));
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }
}
