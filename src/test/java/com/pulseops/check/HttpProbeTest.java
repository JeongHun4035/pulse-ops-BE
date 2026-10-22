package com.pulseops.check;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;

class HttpProbeTest {
    private final UrlPolicy policy = new UrlPolicy() {
        @Override protected java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
            return new java.net.InetAddress[]{java.net.InetAddress.getByName("8.8.8.8")};
        }
    };
    @ParameterizedTest @ValueSource(ints = {200, 204, 301, 399, 400, 404, 503, 599})
    void measuresHeadersAndClassifiesStatus(int code) {
        HttpProbe probe = new HttpProbe(policy, Clock.systemUTC(), 5) {
            @Override protected int request(UrlPolicy.Target target) throws Exception { Thread.sleep(20); return code; }
        };
        var result = probe.check("https://example.com");
        assertThat(result.status()).isEqualTo(code < 400 ? "UP" : "DOWN");
        assertThat(result.httpStatusCode()).isEqualTo(code);
        assertThat(result.responseTimeMs()).isGreaterThanOrEqualTo(15);
    }
    @Test void timeoutAndNetworkErrorsHaveNullMeasurements() {
        for (IOException error : new IOException[]{new SocketTimeoutException(), new IOException("connection refused")}) {
            HttpProbe probe = new HttpProbe(policy, Clock.systemUTC(), 5) {
                @Override protected int request(UrlPolicy.Target target) throws Exception { throw error; }
            };
            var result = probe.check("https://example.com");
            assertThat(result.status()).isEqualTo("DOWN");
            assertThat(result.httpStatusCode()).isNull();
            assertThat(result.responseTimeMs()).isNull();
            assertThat(result.errorMessage()).isNotBlank();
        }
    }
}
