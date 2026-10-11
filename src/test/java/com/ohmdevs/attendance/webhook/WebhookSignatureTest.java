package com.ohmdevs.attendance.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

    @Test
    void theSignatureIsTheHmacSha256OfTimestampDotBody() {
        // Expected value computed outside this code base, with .NET's HMACSHA256
        // over the text 1790000000.{"events":[]} and the key whsec_test.
        assertThat(WebhookSignature.sign("whsec_test", 1_790_000_000L, "{\"events\":[]}"))
                .isEqualTo("81bae186ea58f8c1d7197e1a4277f22f94b00e36734942329ca96618ba29ca1a");
    }

    @Test
    void theHeaderCarriesTheTimestampThatWasSigned() {
        String header = WebhookSignature.header("secret", 1_790_000_000L, "{\"events\":[]}");

        assertThat(header).isEqualTo("t=1790000000,v1="
                + WebhookSignature.sign("secret", 1_790_000_000L, "{\"events\":[]}"));
    }

    @Test
    void aDifferentBodyTimestampOrSecretGivesADifferentSignature() {
        String signature = WebhookSignature.sign("secret", 100, "body");

        assertThat(WebhookSignature.sign("secret", 100, "body")).isEqualTo(signature);
        assertThat(WebhookSignature.sign("secret", 100, "body ")).isNotEqualTo(signature);
        assertThat(WebhookSignature.sign("secret", 101, "body")).isNotEqualTo(signature);
        assertThat(WebhookSignature.sign("other", 100, "body")).isNotEqualTo(signature);
    }

    @Test
    void secretsAreLongAndDifferentEachTime() {
        String secret = WebhookSignature.generateSecret();

        assertThat(secret).startsWith("whsec_").hasSizeGreaterThan(40);
        assertThat(WebhookSignature.generateSecret()).isNotEqualTo(secret);
    }

    @Test
    void thePauseDoublesWithEachFailureUpToTheMaximum() {
        WebhookProperties properties = new WebhookProperties(Duration.ofMillis(500), 100, Duration.ofSeconds(5),
                Duration.ofSeconds(1), Duration.ofMinutes(5), 10);

        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffAfter(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.backoffAfter(9)).isEqualTo(Duration.ofSeconds(256));
        assertThat(properties.backoffAfter(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(500)).isEqualTo(Duration.ofMinutes(5));
    }
}
