package com.pcbuilder.bundle.repository;

import com.pcbuilder.bundle.entity.Bundle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BundleRepository extends JpaRepository<Bundle, Long> {
    Page<Bundle> findByUserId(Long userId, Pageable pageable);
    Optional<Bundle> findByIdAndUserId(Long id, Long userId);
}
