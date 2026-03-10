package org.ecommerce.backend;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router; // Import Router
import io.vertx.ext.web.handler.FileSystemAccess;
import io.vertx.ext.web.handler.StaticHandler; // Import StaticHandler
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject; // Import Inject
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.transaction.Transactional;
import org.ecommerce.common.enums.StaffRoleEn;
import org.ecommerce.common.entity.StaffUserEntity;

@ApplicationScoped
public class Startup {

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Inject
    Router router; // Inject the Vert.x Router

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        // 1. Folder Verification logic
        java.io.File folder = new java.io.File(storagePath);
        if (!folder.exists()) {
            boolean created = folder.mkdirs();
            System.out.println("DEBUG:: Storage folder created: " + created);
        } else {
            System.out.println("DEBUG:: Storage path: " + folder);
        }

        router.route("/static/images/*")
                .handler(StaticHandler.create(FileSystemAccess.ROOT, storagePath)
                        .setCachingEnabled(false));

        System.out.println("DEBUG:: Image server active for: " + storagePath);

        // 3. User Seed logic
        if (StaffUserEntity.count() == 0) {
            StaffUserEntity admin = new StaffUserEntity();
            admin.username = "admin";
            admin.email = "admin@gmail.com";
            admin.fullName = "System Administrator";
            admin.role = StaffRoleEn.SUPER_ADMIN;
            admin.passwordHash = BcryptUtil.bcryptHash("Admin@123");
            admin.isActive = true;
            admin.persist();
        }
    }
}