package com.pulseops.site;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "check_results")
public class CheckResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long siteId;
    @Column(nullable = false) private Instant checkedAt;
    @Column(nullable = false, length = 10) private String status;
    private Integer httpStatusCode;
    private Long responseTimeMs;
    @Column(length = 500) private String errorMessage;
    protected CheckResult() {}
    public Long getSiteId() { return siteId; }
    public Instant getCheckedAt() { return checkedAt; }
    public String getStatus() { return status; }
    public Integer getHttpStatusCode() { return httpStatusCode; }
    public Long getResponseTimeMs() { return responseTimeMs; }
}
