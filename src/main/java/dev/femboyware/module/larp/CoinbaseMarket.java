package dev.femboyware.module.larp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.femboyware.FemboyWare;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real crypto prices for the phone's Coinbase app, from Coinbase's public market data (no account
 * needed): each coin's price and 24 hour change, and its price history for each chart range.
 * Everything's fetched on one background thread and cached; until it arrives (or with no
 * internet) the app falls back to its made-up prices.
 */
final class CoinbaseMarket {
	/** Seconds per candle, and how far back, for 1H, 1D, 1W, 1M, 1Y and ALL. */
	private static final int[] GRANULARITY = {60, 300, 3600, 21600, 86400, 86400};
	private static final long[] SPAN = {3600, 86_400, 604_800, 2_592_000, 31_536_000L, 31_536_000L * 6};
	/** How long a chart stays fresh, per range, in milliseconds. */
	private static final long[] CHART_FRESH = {60_000, 120_000, 600_000, 1_800_000, 3_600_000, 3_600_000};
	private static final long PRICE_FRESH = 15_000, RETRY_AFTER = 30_000;
	private static final int MAX_POINTS = 240;

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "femboyware coinbase prices");
		thread.setDaemon(true);
		return thread;
	});
	/** Ticker to {last price, price 24 hours ago}. */
	private static final Map<String, double[]> STATS = new ConcurrentHashMap<>();
	/** "ticker range" to closing prices, oldest first. */
	private static final Map<String, double[]> CHARTS = new ConcurrentHashMap<>();
	/** When each thing was last fetched (or tried), so it isn't asked for again too soon. */
	private static final Map<String, Long> TRIED = new ConcurrentHashMap<>();
	private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
	private static boolean warned;

	private CoinbaseMarket() {
	}

	/** Whether Coinbase lists it against dollars (USDC is just a dollar). */
	private static boolean listed(String ticker) {
		return !ticker.equals("USDC");
	}

	/** The real price now, or NaN until it's been fetched. */
	static double price(String ticker) {
		if (!listed(ticker)) return 1;
		refreshStats(ticker);
		double[] stats = STATS.get(ticker);
		return stats == null ? Double.NaN : stats[0];
	}

	/** How much it's moved in 24 hours, as a fraction, or NaN until it's been fetched. */
	static double change(String ticker) {
		if (!listed(ticker)) return 0;
		double[] stats = STATS.get(ticker);
		return stats == null || stats[1] <= 0 ? Double.NaN : stats[0] / stats[1] - 1;
	}

	/** Closing prices over a range (0 to 5, as above), oldest first; null until fetched. */
	static double[] chart(String ticker, int range) {
		if (!listed(ticker)) return null;
		String key = ticker + " " + range;
		double[] closes = CHARTS.get(key);
		long now = System.currentTimeMillis();
		Long tried = TRIED.get(key);
		long fresh = closes == null ? RETRY_AFTER : CHART_FRESH[range];
		if ((tried == null || now - tried > fresh) && QUEUED.add(key)) {
			TRIED.put(key, now);
			WORKER.execute(() -> {
				try {
					double[] fetched = fetchChart(ticker, range);
					if (fetched != null && fetched.length > 1) CHARTS.put(key, fetched);
				} finally {
					QUEUED.remove(key);
				}
			});
		}
		return closes;
	}

	private static void refreshStats(String ticker) {
		String key = "stats " + ticker;
		long now = System.currentTimeMillis();
		Long tried = TRIED.get(key);
		if (tried != null && now - tried < (STATS.containsKey(ticker) ? PRICE_FRESH : RETRY_AFTER)) return;
		if (!QUEUED.add(key)) return;
		TRIED.put(key, now);
		WORKER.execute(() -> {
			try {
				JsonElement json = get("https://api.exchange.coinbase.com/products/" + ticker + "-USD/stats");
				if (json != null && json.isJsonObject()) {
					JsonObject stats = json.getAsJsonObject();
					double last = stats.get("last").getAsDouble(), open = stats.get("open").getAsDouble();
					if (last > 0) STATS.put(ticker, new double[]{last, open});
				}
			} catch (RuntimeException e) {
				problem(e);
			} finally {
				QUEUED.remove(key);
			}
		});
	}

	/** A range's candles, a page of 300 at a time back to its start, thinned out to at most MAX_POINTS. */
	private static double[] fetchChart(String ticker, int range) {
		int granularity = GRANULARITY[range];
		long end = System.currentTimeMillis() / 1000, start = end - SPAN[range];
		TreeMap<Long, Double> closes = new TreeMap<>();
		for (long pageEnd = end; pageEnd > start; pageEnd -= 300L * granularity) {
			long pageStart = Math.max(start, pageEnd - 300L * granularity);
			JsonElement json = get("https://api.exchange.coinbase.com/products/" + ticker + "-USD/candles?granularity=" + granularity
				+ "&start=" + Instant.ofEpochSecond(pageStart) + "&end=" + Instant.ofEpochSecond(pageEnd));
			if (json == null || !json.isJsonArray()) break;
			JsonArray candles = json.getAsJsonArray();
			// [time, low, high, open, close, volume], newest first
			for (JsonElement candle : candles) {
				JsonArray c = candle.getAsJsonArray();
				closes.put(c.get(0).getAsLong(), c.get(4).getAsDouble());
			}
			if (candles.isEmpty()) break; // nothing older: the coin's younger than the range
			try {
				Thread.sleep(150); // well under the public rate limit
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (closes.size() < 2) return null;
		double[] all = closes.values().stream().mapToDouble(Double::doubleValue).toArray();
		if (all.length <= MAX_POINTS) return all;
		double[] thin = new double[MAX_POINTS];
		for (int i = 0; i < MAX_POINTS; i++) thin[i] = all[(int) ((long) i * (all.length - 1) / (MAX_POINTS - 1))];
		return thin;
	}

	private static JsonElement get(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
				.header("User-Agent", "femboyware/" + FemboyWare.VERSION).header("Accept", "application/json").GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) return null;
			return JsonParser.parseString(response.body());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			problem(e);
			return null;
		}
	}

	private static void problem(Exception e) {
		if (warned) return;
		warned = true;
		FemboyWare.LOGGER.info("Couldn't get Coinbase prices, using made-up ones: {}", e.toString());
	}
}
