package com.pulseops.site;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class SiteDtos {
    private SiteDtos() {}
    public record Create(@NotBlank @Size(max = 200) String name,
                         @NotBlank @Size(max = 2048) String url,
                         @NotNull @Interval Integer intervalSeconds) {}
    public record Patch(@Size(min = 1, max = 200) @Pattern(regexp = ".*\\S.*", flags = Pattern.Flag.DOTALL) String name,
                        @Interval Integer intervalSeconds, Boolean enabled) {}
    public record View(Long id, String name, String url, int intervalSeconds, boolean enabled,
                       String status, Integer httpStatusCode, Long responseTimeMs,
                       Instant lastCheckedAt, Instant nextCheckAt) {
        static View of(Site site, CheckResult last) {
            return new View(site.getId(), site.getName(), site.getUrl(), site.getIntervalSeconds(),
                site.isEnabled(), last == null ? "UNKNOWN" : last.getStatus(),
                last == null ? null : last.getHttpStatusCode(), last == null ? null : last.getResponseTimeMs(),
                last == null ? null : last.getCheckedAt(), site.getNextCheckAt());
        }
    }
    public record Point(Instant checkedAt, String status, Integer httpStatusCode, Long responseTimeMs) {
        static Point of(CheckResult r) { return new Point(r.getCheckedAt(), r.getStatus(), r.getHttpStatusCode(), r.getResponseTimeMs()); }
    }
    public record History(Long siteId, List<Point> history) {}
}
