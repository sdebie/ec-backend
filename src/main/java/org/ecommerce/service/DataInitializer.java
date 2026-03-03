package org.ecommerce.service;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.ecommerce.common.enums.StaffRoleEn;
import org.ecommerce.persistance.entity.StaffUser;

@ApplicationScoped
public class DataInitializer {

    @Transactional
    void onStart(@Observes StartupEvent ev) {
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