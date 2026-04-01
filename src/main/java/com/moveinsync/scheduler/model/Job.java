package com.moveinsync.scheduler.model;

import java.time.Instant;
import java.util.UUID;

public record Job(UUID id, UUID tenantId, String kind, String cron, Instant nextFireAt, String payload,
                  int priority, int maxRunSeconds, String misfire, int shard, boolean enabled) {}
