package com.ohmdevs.attendance.webhook;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ohmdevs.attendance.webhook.WebhookStore.Subscription;

/** Administration API for the systems that receive punch events over HTTP. */
@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController {

    private final WebhookStore store;
    private final Clock clock;

    WebhookController(WebhookStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SubscriptionWithSecretResponse subscribe(@Valid @RequestBody SubscribeRequest request) {
        UUID id = UUID.randomUUID();
        String secret = WebhookSignature.generateSecret();
        store.create(id, validUrl(request.url()), request.description(), secret, clock.instant());
        return new SubscriptionWithSecretResponse(require(id), secret);
    }

    @GetMapping
    List<SubscriptionResponse> list() {
        return store.list().stream().map(SubscriptionResponse::of).toList();
    }

    @GetMapping("/{id}")
    SubscriptionResponse get(@PathVariable UUID id) {
        return require(id);
    }

    @PostMapping("/{id}/rotate-secret")
    SubscriptionWithSecretResponse rotateSecret(@PathVariable UUID id) {
        String secret = WebhookSignature.generateSecret();
        found(store.replaceSecret(id, secret));
        return new SubscriptionWithSecretResponse(require(id), secret);
    }

    /** Starts sending again at once, from the oldest event still queued. */
    @PostMapping("/{id}/resume")
    SubscriptionResponse resume(@PathVariable UUID id) {
        found(store.resume(id, clock.instant()));
        return require(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@PathVariable UUID id) {
        found(store.delete(id));
    }

    private SubscriptionResponse require(UUID id) {
        return store.find(id).map(SubscriptionResponse::of).orElseThrow(WebhookController::notFound);
    }

    private static void found(boolean found) {
        if (!found) {
            throw notFound();
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook subscription not found");
    }

    private static String validUrl(String url) {
        try {
            URI uri = new URI(url);
            boolean http = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
            if (http && uri.getHost() != null) {
                return url;
            }
        } catch (URISyntaxException e) {
            // Rejected below, like a well-formed address of the wrong kind.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'url' must be an absolute http or https address");
    }

    record SubscribeRequest(
            @NotBlank @Size(max = 2000) String url,
            @Size(max = 200) String description) {
    }

    record SubscriptionResponse(UUID id, String url, String description, String status, int consecutiveFailures,
                                String lastError, Instant lastDeliveredAt, long pendingEvents, Instant createdAt) {

        static SubscriptionResponse of(Subscription subscription) {
            return new SubscriptionResponse(subscription.id(), subscription.url(), subscription.description(),
                    subscription.status(), subscription.consecutiveFailures(), subscription.lastError(),
                    subscription.lastDeliveredAt(), subscription.pendingEvents(), subscription.createdAt());
        }
    }

    /** The only responses that carry a signing secret: subscription and secret rotation. */
    record SubscriptionWithSecretResponse(SubscriptionResponse subscription, String secret) {
    }
}
