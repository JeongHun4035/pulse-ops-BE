package com.pulseops.check;

import com.pulseops.web.ApiException;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.Locale;

@Component
public class UrlPolicy {
    public record Target(URI uri, InetAddress[] addresses) {
        public Target { addresses = addresses.clone(); }
        @Override public InetAddress[] addresses() { return addresses.clone(); }
    }
    public Target validate(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException();
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal") || host.equals("metadata.google.internal")) throw new IllegalArgumentException();
            String path = uri.getRawPath();
            URI canonical = URI.create("https://" + host + (path == null || path.isEmpty() ? "/" : path)
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())).normalize();
            InetAddress[] addresses = resolve(host);
            if (addresses.length == 0) throw new IllegalArgumentException();
            for (InetAddress address : addresses) if (!isPublic(address)) throw new IllegalArgumentException();
            return new Target(canonical, addresses);
        } catch (Exception e) {
            throw new ApiException(400, "URL must resolve exclusively to public HTTPS destinations on port 443");
        }
    }
    protected InetAddress[] resolve(String host) throws UnknownHostException { return InetAddress.getAllByName(host); }
    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int a = b[0] & 255, c = b[1] & 255;
        if (b.length == 4) {
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                || (a == 100 && c >= 64 && c <= 127)
                || (a == 169 && c == 254) || (a == 172 && c >= 16 && c <= 31)
                || (a == 192 && (c == 0 || c == 168 || c == 88))
                || (a == 198 && (c == 18 || c == 19 || c == 51))
                || (a == 203 && c == 0));
        }
        // Only native global-unicast IPv6. Reject ULA, NAT64, transition and documentation ranges.
        return (a & 0xe0) == 0x20
            && !(a == 0x20 && c == 0x01 && (b[2] & 255) <= 1)
            && !(a == 0x20 && c == 0x01 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)
            && !(a == 0x20 && c == 0x02)
            && !(a == 0x3f && c == 0xff);
    }
}
