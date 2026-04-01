package com.moveinsync.scheduler.model;

import java.time.Instant;
import java.util.UUID;

public record Run(UUID id, UUID jobId, UUID tenantId, Instant scheduledAt, String state, int attempt,
                  Instant availableAt, Instant leaseUntil, String owner, long fence, String idempotencyKey) {}
