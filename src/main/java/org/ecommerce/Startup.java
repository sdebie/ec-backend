package org.ecommerce;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.transaction.Transactional;
import org.ecommerce.common.enums.StaffRoleEn;
import org.ecommerce.persistance.entity.StaffUser;

@ApplicationScoped
public class Startup {

    @ConfigProperty(name = "storage.path")
    String storagePath;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        java.io.File folder = new java.io.File(storagePath);
        if (!folder.exists()) {
            boolean created = folder.mkdirs();
            System.out.println("DEBUG:: Storage folder created: " + created);
        }
        else{
            System.out.println("DEBUG:: Storage path: " + folder);
        }

        // Only create the user if the table is empty
        if (StaffUser.count() == 0) {
            StaffUser admin = new StaffUser();
            admin.username = "admin";
            admin.email = "admin@gmail.com"; // Updated as requested
            admin.fullName = "System Administrator";
            admin.role = StaffRoleEn.SUPER_ADMIN;

            // Password set to Admin@123
            admin.passwordHash = BcryptUtil.bcryptHash("Admin@123");

            admin.isActive = true;
            admin.persist();
        }
    }

}