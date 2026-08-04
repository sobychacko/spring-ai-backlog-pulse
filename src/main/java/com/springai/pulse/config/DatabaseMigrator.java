/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.springai.pulse.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Runs Flyway migrations explicitly at startup. Spring Boot 4 moved Flyway's autoconfiguration
 * into a separate module that {@code flyway-core} alone does not pull in, so we invoke Flyway
 * directly against the datasource — deterministic and fully under our control. Migration runs in
 * the constructor of this eager bean, before any repository touches the database.
 */
@Component
public class DatabaseMigrator {

	private static final Logger logger = LoggerFactory.getLogger(DatabaseMigrator.class);

	public DatabaseMigrator(DataSource dataSource) {
		MigrateResult result = Flyway.configure()
			.dataSource(dataSource)
			.baselineOnMigrate(true)
			.locations("classpath:db/migration")
			.load()
			.migrate();
		logger.info("Flyway: {} migration(s) applied, schema version now {}", result.migrationsExecuted,
				result.targetSchemaVersion);
	}

}
