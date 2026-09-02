package com.acme.persistence.fixture;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SensorReadingJpaRepository extends JpaRepository<SensorReadingEntity, SensorReadingId> {}
