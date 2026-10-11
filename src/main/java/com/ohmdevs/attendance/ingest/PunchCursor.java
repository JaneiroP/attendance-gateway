package com.ohmdevs.attendance.ingest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * The position of the last punch on a page: the next page starts right after it.
 *
 * <p>A position rather than a page number, so a page costs the same however
 * deep it is, and punches stored while someone is paging neither repeat nor
 * push others off the page. The id breaks ties between punches of the same
 * instant. Callers get it encoded and are meant to pass it back untouched.
 */
record PunchCursor(Instant punchedAtUtc, UUID id) {

    String encode() {
        String text = punchedAtUtc + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException if the text is not a cursor this service produced */
    static PunchCursor decode(String encoded) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = text.indexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("Not a punch cursor");
            }
            return new PunchCursor(Instant.parse(text.substring(0, separator)),
                    UUID.fromString(text.substring(separator + 1)));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Not a punch cursor", e);
        }
    }
}
