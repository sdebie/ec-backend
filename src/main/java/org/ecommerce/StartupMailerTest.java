import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class StartupMailerTest {

    private static final Logger LOG = Logger.getLogger(StartupMailerTest.class);

    @Inject
    ReactiveMailer reactiveMailer;

    //void onStart(@Observes StartupEvent ev) {
    void test() {
        LOG.info("The application is starting... Sending Test Email via Gmail SMTP.");

        reactiveMailer.send(
                Mail.withHtml("shawn.debie@gmail.com",
                        "Quarkus Test: Gmail SMTP Working!",
                        "<h1>Success!</h1><p>Your Quarkus backend is now connected to Gmail SMTP.</p>")
                        .setFrom("shawn.debie@gmail.com")
        ).subscribe().with(
                item -> LOG.info("Test email sent successfully!"),
                failure -> LOG.error("SMTP Configuration Failed: " + failure.getMessage())
        );
    }
}