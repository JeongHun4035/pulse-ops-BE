package com.pulseops.site;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Long> {
    boolean existsByUrl(String url);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Site s where s.id = :id")
    Optional<Site> findLockedById(Long id);
}
