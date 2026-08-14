package org.ecommerce.backend.service.payfast;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises confirmWithPayFast against a local fake of PayFast's validate endpoint,
 * so the real HTTP request/response handling is under test rather than a mock of it.
 */
class PayFastServiceServerConfirmationTest
{
    private PayFastService service;
    private HttpServer fakePayFast;

    @BeforeEach
    void setUp()
    {
        service = new PayFastService();
        service.passphrase = "test-passphrase";
    }

    @AfterEach
    void tearDown()
    {
        if (fakePayFast != null) {
            fakePayFast.stop(0);
        }
    }

    private AtomicReference<String> startFakeValidateServer(int status, String responseBody) throws IOException
    {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        fakePayFast = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakePayFast.createContext("/validate", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            receivedBody.set(new String(requestBytes, StandardCharsets.UTF_8));

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        fakePayFast.start();
        service.validateUrl = "http://localhost:" + fakePayFast.getAddress().getPort() + "/validate";
        return receivedBody;
    }

    @Test
    void returnsTrueWhenPayFastRespondsValid() throws IOException
    {
        startFakeValidateServer(200, "VALID");

        assertTrue(service.confirmWithPayFast("m_payment_id=1&amount_gross=100.00&signature=abc123"));
    }

    @Test
    void postsTheReceivedDataMinusSignatureAndNeverThePassphrase() throws IOException
    {
        AtomicReference<String> receivedBody = startFakeValidateServer(200, "VALID");

        service.confirmWithPayFast("m_payment_id=1&amount_gross=100.00&signature=abc123");

        assertTrue(receivedBody.get().equals("m_payment_id=1&amount_gross=100.00"),
                "expected the signature-stripped param string verbatim, got: " + receivedBody.get());
        assertFalse(receivedBody.get().contains("passphrase"),
                "the merchant passphrase must never be sent in the confirmation request body");
    }

    @Test
    void returnsFalseWhenPayFastRespondsInvalid() throws IOException
    {
        startFakeValidateServer(200, "INVALID");

        assertFalse(service.confirmWithPayFast("m_payment_id=1&signature=abc123"));
    }

    @Test
    void returnsFalseOnNon200Status() throws IOException
    {
        startFakeValidateServer(500, "VALID");

        assertFalse(service.confirmWithPayFast("m_payment_id=1&signature=abc123"));
    }

    @Test
    void returnsFalseWhenPayFastIsUnreachable()
    {
        service.validateUrl = "http://localhost:1/validate";

        assertFalse(service.confirmWithPayFast("m_payment_id=1&signature=abc123"));
    }
}
