package io.smartcat.migration;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Session;
import io.smartcat.migration.exceptions.MigrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migration engine wraps Migrator and provides DSL like API.
 */
public class MigrationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationEngine.class);

    private MigrationEngine() {
    }

    /**
     * Create migrator out of session fully prepared for doing migration of
     * resources.
     *
     * @param session Datastax driver session object
     * @return migrator instance with versioner and session which can migrate
     * resources
     */
    public static Migrator withSession(final Session session, final String schemaVersionCf) {
        return new Migrator(session, schemaVersionCf);
    }

    public static Migrator withSession(final Session session) {
        return new Migrator(session, "schema_version");
    }

    /**
     * Migrator handles migrations and errors.
     */
    public static class Migrator {
        private final Session session;

        private final CassandraVersioner versioner;

        /**
         * Create new Migrator with active Cassandra session.
         *
         * @param session Active Cassandra session
         */
        Migrator(final Session session, final String schemaVersionCf) {
            this.session = session;
            this.versioner = new CassandraVersioner(session, schemaVersionCf);
        }

        public boolean migrate(final MigrationResources resources) {
            return migrate(resources, false);
        }

        /**
         * Method that executes all migration from migration resources that are
         * higher version than db version. If migration fails, method will exit.
         *
         * @param resources Collection of migrations to be executed
         * @return Success of migration
         */
        public boolean migrate(final MigrationResources resources, boolean force) {
            LOGGER.debug("Start migration");

            for (final Migration migration : resources.getMigrations()) {
                final MigrationType type = migration.getType();
                final int migrationVersion = migration.getVersion();

                if (!force) {
                    final int version = versioner.getCurrentVersion(type, ConsistencyLevel.LOCAL_QUORUM);

                    LOGGER.info("Db is version {} for type {}.", version, type.name());
                    LOGGER.info("Compare {} migration version {} with description {}", type.name(), migrationVersion,
                                migration.getDescription());

                    if (migrationVersion <= version) {
                        LOGGER.warn("Skipping migration [{}] with version {} since db is on higher version {}.", migration
                                        .getDescription(),
                                    migrationVersion, version);
                        continue;
                    }

                    LOGGER.debug("Checking version second time with ALL consistency");
                    final int allVersion = versioner.getCurrentVersion(type, ConsistencyLevel.ALL);
                    if (allVersion != version) {
                        LOGGER.error("Version mismatch error, allVersion={}, version={}, migrationVersion={}", allVersion, version, migrationVersion);
                        return false;
                    }
                } else {
                    //Check ConsistencyLevel.ALL is available
                    versioner.getCurrentVersion(type, ConsistencyLevel.ALL);
                }

                migration.setSession(session);

                final long start = System.currentTimeMillis();
                LOGGER.info("Start executing migration to version {}.", migrationVersion);

                try {
                    migration.execute();
                } catch (final MigrationException e) {
                    LOGGER.error("Failed to execute migration version {}, exception {}!", migrationVersion, e.getMessage());
                    LOGGER.debug("Exception stack trace: {}", e);
                    return false;
                }

                final long end = System.currentTimeMillis();
                final long seconds = (end - start) / 1000;
                LOGGER.info("Migration [{}] to version {} finished in {} seconds.", migration.getDescription(), migrationVersion, seconds);

                if (!versioner.updateVersion(migration)) {
                    LOGGER.error("Db schema update failed for migration version {}!", migrationVersion);
                    return false;
                }
            }

            return true;
        }
    }

}
