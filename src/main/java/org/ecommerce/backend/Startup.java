package org.ecommerce.backend;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.transaction.Transactional;
import org.ecommerce.common.enums.StaffRoleEn;
import org.ecommerce.common.entity.StaffUserEntity;
import org.jboss.logging.Logger;

@ApplicationScoped
public class Startup {

    private static final Logger LOG = Logger.getLogger(Startup.class);

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @ConfigProperty(name = "admin.bootstrap.email")
    String bootstrapAdminEmail;

    @ConfigProperty(name = "admin.bootstrap.password")
    String bootstrapAdminPassword;

    @Inject
    Router router; // Inject the Vert.x Router

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        // 1. Folder Verification logic
        java.io.File folder = new java.io.File(storagePath);
        if (!folder.exists()) {
            boolean created = folder.mkdirs();
            LOG.info("Storage folder created (" + created + "): " + folder);
        }

        router.route("/static/images/*")
                .handler(StaticHandler.create(FileSystemAccess.ROOT, storagePath)
                        .setCachingEnabled(false));

        LOG.info("Image server active for: " + storagePath);

        // 3. User Seed logic
        if (StaffUserEntity.count() == 0) {
            StaffUserEntity admin = new StaffUserEntity();
            admin.email = bootstrapAdminEmail;
            admin.fullName = "System Administrator";
            admin.role = StaffRoleEn.SUPER_ADMIN;
            admin.passwordHash = BcryptUtil.bcryptHash(bootstrapAdminPassword);
            admin.isActive = true;
            admin.persist();
        }
    }

    /**
     * Validates the CORS_ORIGINS env var in production.
     * An unset var fails to resolve on its own (${CORS_ORIGINS} with no default),
     * but an empty or whitespace-only value resolves successfully and would boot
     * with a zero-origin config. This guard catches that case (REQ 1.2).
     */
    void validateCorsOrigins(@Observes StartupEvent ev) {
        if (LaunchMode.current() != LaunchMode.NORMAL) {
            return; // Only enforce in %prod
        }
        String origins = ConfigProvider.getConfig()
                .getOptionalValue("CORS_ORIGINS", String.class).orElse("");
        if (origins.isBlank()) {
            throw new ConfigurationException(
                    "CORS_ORIGINS must be set to a non-blank origin allowlist in production");
        }
    }
}