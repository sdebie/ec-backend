package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PasswordResetNotificationService
{
    private static final Logger LOG = Logger.getLogger(PasswordResetNotificationService.class);

    @Inject
    MailTemplate password_reset_code;

    @ConfigProperty(name = "quarkus.mailer.from")
    String mailerFrom;

    public void sendResetCode(String email, String code, int expiresInMinutes)
    {
        if (mailerFrom == null || mailerFrom.isBlank()) {
            LOG.error("[PasswordResetNotificationService] from-address is blank — skipping reset code send");
            return;
        }
        if (email == null || email.isBlank()) {
            LOG.warn("[PasswordResetNotificationService] skipped: no valid recipient for reset code");
            return;
        }

        password_reset_code.to(email)
                .from(mailerFrom)
                .subject("Your password reset code")
                .data("code", code)
                .data("expiresInMinutes", expiresInMinutes)
                .send()
                .subscribe().with(
                        success -> LOG.infof("[PasswordResetNotificationService] reset code delivered to %s", email),
                        failure -> LOG.errorf(failure, "[PasswordResetNotificationService] reset code delivery failed for %s", email)
                );
    }
}

