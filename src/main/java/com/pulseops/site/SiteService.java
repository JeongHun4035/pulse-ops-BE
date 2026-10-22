package com.pulseops.site;

import com.pulseops.web.ApiException;
import com.pulseops.check.UrlPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;
import static com.pulseops.site.SiteDtos.*;

@Service
public class SiteService {
    private final SiteRepository sites;
    private final CheckResultRepository results;
    private final UrlPolicy urls;
    private final Clock clock;
    public SiteService(SiteRepository sites, CheckResultRepository results, UrlPolicy urls, Clock clock) {
        this.sites = sites; this.results = results; this.urls = urls; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public List<View> list() {
        Map<Long, CheckResult> latest = new HashMap<>();
        results.findLatestForAllSites().forEach(r -> latest.put(r.getSiteId(), r));
        return sites.findAll(org.springframework.data.domain.Sort.by("id")).stream()
            .map(s -> View.of(s, latest.get(s.getId()))).toList();
    }
    public View create(Create request) {
        // Resolve outside a database transaction. The database unique constraint handles races.
        String url = urls.validate(request.url()).uri().toASCIIString();
        if (sites.existsByUrl(url)) throw new ApiException(409, "URL already registered");
        return View.of(sites.saveAndFlush(new Site(request.name().trim(), url, request.intervalSeconds(), clock.instant())), null);
    }
    @Transactional
    public View patch(Long id, Patch request) {
        Site site = sites.findLockedById(id).orElseThrow(() -> new ApiException(404, "Site not found"));
        site.update(request.name() == null ? null : request.name().trim(), request.intervalSeconds(), request.enabled(), clock.instant());
        CheckResult last = results.findFirstBySiteIdOrderByCheckedAtDescIdDesc(id).orElse(null);
        return View.of(site, last);
    }
    @Transactional
    public void delete(Long id) {
        Site site = sites.findLockedById(id).orElseThrow(() -> new ApiException(404, "Site not found"));
        sites.delete(site);
    }
    @Transactional(readOnly = true)
    public History history(Long id, Integer hours, Integer minutes, boolean all) {
        if (all) {
            if (hours != null || minutes != null) throw new ApiException(400, "all=true cannot be combined with hours or minutes");
            if (!sites.existsById(id)) throw new ApiException(404, "Site not found");
            return new History(id, results.findBySiteIdOrderByCheckedAtAscIdAsc(id).stream().map(Point::of).toList());
        }
        if (hours != null && minutes != null) throw new ApiException(400, "Specify either hours or minutes, not both");
        long seconds;
        if (minutes != null) {
            if (minutes != 1 && minutes != 5 && minutes != 30) throw new ApiException(400, "minutes must be 1, 5, or 30");
            seconds = minutes * 60L;
        } else {
            int selectedHours = hours == null ? 24 : hours;
            if (selectedHours != 1 && selectedHours != 6 && selectedHours != 24) throw new ApiException(400, "hours must be 1, 6, or 24");
            seconds = selectedHours * 3600L;
        }
        if (!sites.existsById(id)) throw new ApiException(404, "Site not found");
        var now = clock.instant();
        return new History(id, results.findBySiteIdAndCheckedAtBetweenOrderByCheckedAtAscIdAsc(id,
            now.minusSeconds(seconds), now).stream().map(Point::of).toList());
    }
}
