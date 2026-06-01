package org.ecommerce.backend;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped; // Add this

@ApplicationScoped
public class ImageStaticResourceConfig
{
    @Inject
    Router router;

    @ConfigProperty(name = "storage.path")
    String storagePath;

    void installRoute(@Observes StartupEvent ev)
    {
        // Log it to your Pretoria console so you know it's working
        System.out.println("DEBUG:: Serving images from: " + storagePath);

        // Map the URL /static/images/ to your Mac folder
        router.route("/static/images/*")
                .handler(StaticHandler.create()
                        .setAllowRootFileSystemAccess(true)
                        .setWebRoot(storagePath)
                        .setCachingEnabled(false)); // Disable cache for dev
    }
}