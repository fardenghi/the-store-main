package com.amazon.sample.assistant.products.index;

import java.time.Duration;

/** Contadores de una sincronización (D4, paso 7). */
public record SyncReport(
    int catalogProducts,
    int embedded,
    int payloadUpdated,
    int deleted,
    int unchanged,
    int providerRequests,
    Duration duration) {
}
