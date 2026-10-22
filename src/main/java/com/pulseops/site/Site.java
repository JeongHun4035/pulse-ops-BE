package com.pulseops.site;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sites")
public class Site {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200) private String name;
    @Column(nullable = false, unique = true, length = 2048) private String url;
    @Column(nullable = false) private int intervalSeconds;
    @Column(nullable = false) private boolean enabled;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant nextCheckAt;
    protected Site() {}
    public Site(String name, String url, int interval, Instant now) {
        this.name = name; this.url = url; intervalSeconds = interval;
        enabled = true; createdAt = now; nextCheckAt = now;
    }
    public void update(String name, Integer interval, Boolean enabled, Instant now) {
        if (name != null) this.name = name;
        if (interval != null && interval != intervalSeconds) {
            intervalSeconds = interval;
            nextCheckAt = now.plusSeconds(interval);
        }
        if (enabled != null) {
            if (enabled && !this.enabled) nextCheckAt = now;
            this.enabled = enabled;
        }
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public int getIntervalSeconds() { return intervalSeconds; }
    public boolean isEnabled() { return enabled; }
    public Instant getNextCheckAt() { return nextCheckAt; }
}
