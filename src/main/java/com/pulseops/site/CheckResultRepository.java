package com.pulseops.site;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {
    java.util.Optional<CheckResult> findFirstBySiteIdOrderByCheckedAtDescIdDesc(Long siteId);
    @Query(value = "SELECT DISTINCT ON (site_id) * FROM check_results ORDER BY site_id, checked_at DESC, id DESC", nativeQuery = true)
    List<CheckResult> findLatestForAllSites();
    // The FK is represented as an ID to keep response serialization independent of entities.
    List<CheckResult> findBySiteIdAndCheckedAtBetweenOrderByCheckedAtAscIdAsc(Long siteId, Instant start, Instant end);
    List<CheckResult> findBySiteIdOrderByCheckedAtAscIdAsc(Long siteId);
}
