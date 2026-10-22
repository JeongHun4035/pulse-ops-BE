package com.pulseops.check;

import com.pulseops.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class UrlPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
        "192.168.1.1", "100.100.100.200", "224.0.0.1", "240.0.0.1", "198.18.0.1", "192.0.2.1",
        "198.51.100.1", "203.0.113.1", "::", "::1", "fc00::1", "fe80::1", "ff02::1",
        "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::", "2001:db8::1", "2001::1", "3fff::1"})
    void rejectsNonPublicAddresses(String ip) throws Exception {
        assertThat(UrlPolicy.isPublic(InetAddress.getByName(ip))).isFalse();
    }
    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void acceptsPublicAddresses(String ip) throws Exception {
        assertThat(UrlPolicy.isPublic(InetAddress.getByName(ip))).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "https://localhost", "https://x.localhost", "https://x.local",
        "https://user@example.com", "https://example.com/#fragment", "https://example.com:8443", "file:///etc/passwd",
        "https://metadata.google.internal", "https:///missing", "not a url", "https://example.com\\@127.0.0.1"})
    void rejectsUnsafeUrlsBeforeDns(String url) {
        UrlPolicy policy = new UrlPolicy() {
            @Override protected InetAddress[] resolve(String host) { throw new AssertionError("DNS must not run"); }
        };
        assertThatThrownBy(() -> policy.validate(url)).isInstanceOf(ApiException.class);
    }
    @Test void rejectsMixedDnsAndRevalidatesAtCheckTime() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        UrlPolicy policy = new UrlPolicy() {
            @Override protected InetAddress[] resolve(String host) throws UnknownHostException {
                return lookups.incrementAndGet() == 1 ? new InetAddress[]{InetAddress.getByName("8.8.8.8")}
                    : new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")};
            }
        };
        assertThat(policy.validate("HTTPS://EXAMPLE.COM:443").uri().toString()).isEqualTo("https://example.com/");
        var outcome = new HttpProbe(policy, java.time.Clock.systemUTC(), 5).check("https://example.com/");
        assertThat(outcome.status()).isEqualTo("DOWN");
        assertThat(outcome.httpStatusCode()).isNull();
        assertThat(outcome.responseTimeMs()).isNull();
        assertThat(lookups).hasValue(2);
    }
    @Test void unresolvedDnsIsValidationFailure() {
        UrlPolicy policy = new UrlPolicy() {
            @Override protected InetAddress[] resolve(String host) throws UnknownHostException { throw new UnknownHostException(); }
        };
        assertThatThrownBy(() -> policy.validate("https://example.com")).isInstanceOf(ApiException.class);
    }
}
