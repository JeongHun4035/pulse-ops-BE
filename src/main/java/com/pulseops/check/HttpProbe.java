package com.pulseops.check;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.time.*;

@Component
public class HttpProbe {
    public record Outcome(Instant checkedAt, String status, Integer httpStatusCode, Long responseTimeMs, String errorMessage) {}
    private final UrlPolicy urls;
    private final Clock clock;
    private final Timeout timeout;
    public HttpProbe(UrlPolicy urls, Clock clock, @Value("${pulseops.timeout-seconds:5}") int seconds) {
        if (seconds < 1 || seconds > 30) throw new IllegalArgumentException("HTTP timeout must be between 1 and 30 seconds");
        this.urls = urls; this.clock = clock; timeout = Timeout.ofSeconds(seconds);
    }
    public Outcome check(String url) {
        Instant checkedAt = clock.instant();
        try {
            // Resolve again for EVERY check. Only this immutable result can be used by the connection manager.
            UrlPolicy.Target target = urls.validate(url);
            long start = System.nanoTime();
            int code = request(target);
            return new Outcome(checkedAt, code >= 200 && code <= 399 ? "UP" : "DOWN", code,
                Duration.ofNanos(System.nanoTime() - start).toMillis(), null);
        } catch (Exception e) {
            return new Outcome(checkedAt, "DOWN", null, null, "Check failed: " + e.getClass().getSimpleName());
        }
    }
    protected int request(UrlPolicy.Target target) throws Exception {
        String expectedHost = target.uri().getHost();
        DnsResolver pinned = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!expectedHost.equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected destination");
                return target.addresses();
            }
            @Override public String resolveCanonicalHostname(String host) throws UnknownHostException {
                if (!expectedHost.equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected destination");
                return expectedHost;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned).setTlsSocketStrategy(tlsStrategy())
            .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build()).build();
        var client = HttpClients.custom().setConnectionManager(manager)
            .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(timeout).build())
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().disableContentCompression().build();
        try {
            // URI hostname remains intact for TLS SNI and certificate hostname validation.
            // Read headers only; immediate connection shutdown avoids draining an arbitrary response body.
            var response = client.executeOpen(null, new HttpGet(target.uri()), null);
            return response.getCode();
        } finally {
            client.close(CloseMode.IMMEDIATE);
        }
    }
    protected TlsSocketStrategy tlsStrategy() { return DefaultClientTlsStrategy.createDefault(); }
}
