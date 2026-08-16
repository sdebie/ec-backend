package org.ecommerce.backend.service;

import io.quarkus.mailer.MailTemplate;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PasswordResetNotificationService#sendResetCode}. This is the
 * delivery mechanism for the storefront's live "Forgot password" flow — before this
 * fix it only logged, so no customer ever received their reset code. Only the transport
 * (MailTemplate fluent chain) is mocked; the real notifier logic runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordResetNotificationService — sendResetCode delivers real mail")
class PasswordResetNotificationServiceTest
{
    @InjectMocks
    private PasswordResetNotificationService notifier;

    @Mock
    private MailTemplate password_reset_code;

    private static final String CONFIGURED_FROM = "no-reply@store.co.za";

    @BeforeEach
    void setUp() throws Exception
    {
        var fromField = PasswordResetNotificationService.class.getDeclaredField("mailerFrom");
        fromField.setAccessible(true);
        fromField.set(notifier, CONFIGURED_FROM);
    }

    private MailTemplate.MailTemplateInstance stubMailTemplateChain()
    {
        MailTemplate.MailTemplateInstance instance = mock(MailTemplate.MailTemplateInstance.class);
        when(password_reset_code.to(anyString())).thenReturn(instance);
        when(instance.from(anyString())).thenReturn(instance);
        when(instance.subject(anyString())).thenReturn(instance);
        when(instance.data(anyString(), any())).thenReturn(instance);
        when(instance.send()).thenReturn(Uni.createFrom().voidItem());
        return instance;
    }

    @Test
    @DisplayName("happy path sends a real email with the code and expiry")
    void sendResetCode_happyPath_sendsRealEmail()
    {
        MailTemplate.MailTemplateInstance instance = stubMailTemplateChain();

        notifier.sendResetCode("shopper@example.com", "482913", 10);

        verify(password_reset_code).to("shopper@example.com");
        verify(instance).from(CONFIGURED_FROM);
        verify(instance).data("code", "482913");
        verify(instance).data("expiresInMinutes", 10);
        verify(instance).send();
    }

    @Test
    @DisplayName("blank from-address skips send entirely")
    void sendResetCode_blankFrom_skipsSend() throws Exception
    {
        var fromField = PasswordResetNotificationService.class.getDeclaredField("mailerFrom");
        fromField.setAccessible(true);
        fromField.set(notifier, "   ");

        notifier.sendResetCode("shopper@example.com", "482913", 10);

        verifyNoInteractions(password_reset_code);
    }

    @Test
    @DisplayName("blank recipient skips send entirely")
    void sendResetCode_blankEmail_skipsSend()
    {
        notifier.sendResetCode("   ", "482913", 10);

        verifyNoInteractions(password_reset_code);
    }
}
