package com.pulseops.web;

import com.pulseops.site.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.List;
import static com.pulseops.site.SiteDtos.*;

@RestController
@Profile("!worker")
@RequestMapping("/api/v1/sites")
@Validated
public class SiteController {
    private final SiteService service;
    public SiteController(SiteService service) { this.service = service; }
    @GetMapping public List<View> list() { return service.list(); }
    @PostMapping public ResponseEntity<View> create(@Valid @RequestBody Create request) {
        View result = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/sites/" + result.id())).body(result);
    }
    @PatchMapping("/{id}") public View patch(@PathVariable @Positive Long id, @Valid @RequestBody Patch request) {
        return service.patch(id, request);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
        service.delete(id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/{id}/history") public History history(@PathVariable @Positive Long id,
        @io.swagger.v3.oas.annotations.Parameter(description = "조회 시간: 1, 6, 24. 기간 생략 시 24시간")
        @RequestParam(required = false) Integer hours,
        @io.swagger.v3.oas.annotations.Parameter(description = "조회 분: 1, 5, 30. hours와 동시 지정 불가")
        @RequestParam(required = false) Integer minutes,
        @io.swagger.v3.oas.annotations.Parameter(description = "전체 누적 이력 조회. true이면 hours/minutes와 동시 지정 불가")
        @RequestParam(defaultValue = "false") boolean all) { return service.history(id, hours, minutes, all); }
}
