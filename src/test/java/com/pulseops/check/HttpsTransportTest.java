package com.pulseops.check;

import com.sun.net.httpserver.*;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.junit.jupiter.api.*;
import javax.net.ssl.*;
import java.net.*;
import java.security.KeyStore;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class HttpsTransportTest {
    HttpsServer server;
    SSLContext tls;
    ExecutorService executor;
    AtomicInteger requests;
    @BeforeEach void start() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = getClass().getResourceAsStream("/probe.p12")) { keys.load(input, "test-only".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "test-only".toCharArray());
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        requests = new AtomicInteger();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    HttpProbe probe(String path, AtomicInteger lookups) {
        UrlPolicy fixture = new UrlPolicy() {
            @Override public Target validate(String ignored) {
                lookups.incrementAndGet();
                // TEST ONLY: allow loopback and pin a non-resolving hostname to the local TLS server.
                try { return new Target(URI.create("https://probe.example:" + server.getAddress().getPort() + path),
                    new InetAddress[]{InetAddress.getByName("127.0.0.1")}); }
                catch (Exception e) { throw new AssertionError(e); }
            }
        };
        return new HttpProbe(fixture, Clock.systemUTC(), 1) {
            @Override protected TlsSocketStrategy tlsStrategy() { return new DefaultClientTlsStrategy(tls); }
        };
    }
    @Test void pinnedDnsKeepsTlsHostnameAndDoesNotReadBodyOrFollowRedirects() {
        server.createContext("/redirect", exchange -> {
            requests.incrementAndGet(); exchange.getResponseHeaders().add("Location", "https://127.0.0.1/private");
            exchange.sendResponseHeaders(302, 0);
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        AtomicInteger lookups = new AtomicInteger();
        long start = System.nanoTime();
        var result = probe("/redirect", lookups).check("https://probe.example/redirect");
        assertThat(result.status()).isEqualTo("UP");
        assertThat(result.httpStatusCode()).isEqualTo(302);
        assertThat(result.responseTimeMs()).isNotNull();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1800);
        assertThat(lookups).hasValue(1); assertThat(requests).hasValue(1);
    }
    @Test void actualResponseTimeoutIsDownWithNullMeasurements() {
        server.createContext("/slow", exchange -> {
            requests.incrementAndGet();
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        var result = probe("/slow", new AtomicInteger()).check("ignored");
        assertThat(result.status()).isEqualTo("DOWN");
        assertThat(result.httpStatusCode()).isNull(); assertThat(result.responseTimeMs()).isNull();
        assertThat(requests).hasValue(1);
    }
    @Test void actualServerErrorMeasuresResponse() {
        server.createContext("/error", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        var result = probe("/error", new AtomicInteger()).check("ignored");
        assertThat(result.status()).isEqualTo("DOWN"); assertThat(result.httpStatusCode()).isEqualTo(503);
        assertThat(result.responseTimeMs()).isNotNegative();
    }
    @Test void certificateHostnameMismatchFailsClosed() {
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        UrlPolicy fixture = new UrlPolicy() {
            @Override public Target validate(String ignored) {
                try { return new Target(URI.create("https://wrong.example:" + server.getAddress().getPort()),
                    new InetAddress[]{InetAddress.getByName("127.0.0.1")}); } catch (Exception e) { throw new AssertionError(e); }
            }
        };
        var result = new HttpProbe(fixture, Clock.systemUTC(), 1) {
            @Override protected TlsSocketStrategy tlsStrategy() { return new DefaultClientTlsStrategy(tls); }
        }.check("ignored");
        assertThat(result.status()).isEqualTo("DOWN"); assertThat(result.httpStatusCode()).isNull();
        assertThat(requests).hasValue(0);
    }
}
