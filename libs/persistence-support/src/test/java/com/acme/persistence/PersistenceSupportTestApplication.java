package com.acme.persistence;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;

/**
 * This module has no {@code @SpringBootApplication} of its own - it is a library, not a service -
 * so {@code @DataJpaTest}'s upward package search needs a marker to find, and its entity and
 * repository scanning needs {@code @AutoConfigurationPackage} to know where to look. Otherwise
 * empty: every bean a test in this package needs comes from {@code @DataJpaTest}'s own slice
 * autoconfiguration.
 */
@SpringBootConfiguration
@AutoConfigurationPackage
class PersistenceSupportTestApplication {}
