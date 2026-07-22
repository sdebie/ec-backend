package org.ecommerce.backend.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PasswordResetNotificationService
{
    private static final Logger LOG = Logger.getLogger(PasswordResetNotificationService.class);

    public void sendResetLink(String email, String token)
    {
        // Placeholder transport; avoid logging secrets like raw tokens.
        LOG.infof("Password reset requested for %s", email);
    }

    public void sendResetCode(String email, String code, int expiresInMinutes)
    {
        // Placeholder transport. Integrate mail/SMS provider here and avoid logging raw code.
        LOG.infof("Password reset code requested for %s (expires in %d minutes)", email, expiresInMinutes);
    }
}

