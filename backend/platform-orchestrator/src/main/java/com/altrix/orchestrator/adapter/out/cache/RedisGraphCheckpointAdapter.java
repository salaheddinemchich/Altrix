package com.altrix.orchestrator.adapter.out.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Redis-backed {@link BaseCheckpointSaver} for the LangGraph4j migration workflow graph (#173).
 *
 * <p>Each job thread gets a Redis List at key {@code "migraph:cp:{threadId}"}.
 * Checkpoints are LPUSH'd (newest at index 0) as JSON strings. A separate key
 * {@code "migraph:cp:{threadId}:ttl"} is used as a TTL sentinel — both keys share
 * the same 24-hour expiry so stale checkpoint data is automatically cleaned up.
 *
 * <p>JSON serialisation uses Jackson with {@code DefaultTyping.EVERYTHING} so that
 * every value in the state map carries its class name, enabling full round-trip
 * deserialisation of arbitrary domain objects without requiring {@link java.io.Serializable}.
 */
@Slf4j
@Component
public class RedisGraphCheckpointAdapter implements BaseCheckpointSaver {

    private static final String KEY_PREFIX   = "migraph:cp:";
    private static final Duration TTL        = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper        mapper;

    public RedisGraphCheckpointAdapter(StringRedisTemplate redis) {
        this.redis  = redis;
        this.mapper = buildMapper();
    }

    // ── BaseCheckpointSaver ───────────────────────────────────────────────────

    @Override
    public Collection<Checkpoint> list(RunnableConfig config) {
        String key = key(config);
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) return List.of();
        return raw.stream()
                .map(this::deserialize)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Checkpoint> get(RunnableConfig config) {
        String key = key(config);
        if (config.checkPointId().isPresent()) {
            String targetId = config.checkPointId().get();
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null) return Optional.empty();
            return raw.stream()
                    .map(this::deserialize)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(cp -> targetId.equals(cp.getId()))
                    .findFirst();
        }
        // Latest checkpoint is at index 0 (LPUSH)
        String latest = redis.opsForList().index(key, 0);
        return Optional.ofNullable(latest).flatMap(this::deserialize);
    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) {
        String key  = key(config);
        String json = serialize(checkpoint);
        if (json == null) {
            log.warn("Could not serialise checkpoint {} for key '{}' — skipping persist",
                    checkpoint.getId(), key);
            return config;
        }

        if (config.checkPointId().isPresent()) {
            // Replace existing checkpoint in-place
            String targetId = config.checkPointId().get();
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw != null) {
                List<String> updated = new ArrayList<>(raw);
                for (int i = 0; i < updated.size(); i++) {
                    Optional<Checkpoint> existing = deserialize(updated.get(i));
                    if (existing.isPresent() && targetId.equals(existing.get().getId())) {
                        redis.opsForList().set(key, i, json);
                        resetTtl(key);
                        return config;
                    }
                }
            }
        }

        redis.opsForList().leftPush(key, json);
        resetTtl(key);
        return RunnableConfig.builder(config)
                .checkPointId(checkpoint.getId())
                .build();
    }

    @Override
    public Tag release(RunnableConfig config) {
        String key = key(config);
        Collection<Checkpoint> checkpoints = list(config);
        redis.delete(key);
        String threadId = config.threadId().orElse(THREAD_ID_DEFAULT);
        log.debug("Released {} checkpoint(s) for thread '{}'", checkpoints.size(), threadId);
        return new Tag(threadId, checkpoints);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String key(RunnableConfig config) {
        return KEY_PREFIX + config.threadId().orElse(THREAD_ID_DEFAULT);
    }

    private void resetTtl(String key) {
        redis.expire(key, TTL);
    }

    private String serialize(Checkpoint cp) {
        try {
            return mapper.writeValueAsString(cp);
        } catch (Exception e) {
            log.error("Failed to serialise checkpoint {}: {}", cp.getId(), e.getMessage());
            return null;
        }
    }

    private Optional<Checkpoint> deserialize(String json) {
        try {
            return Optional.of(mapper.readValue(json, Checkpoint.class));
        } catch (Exception e) {
            log.error("Failed to deserialise checkpoint JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // JAVA_LANG_OBJECT adds @class whenever the static type is Object —
        // covers every value in Map<String,Object> state without Serializable.
        // More targeted than EVERYTHING and not deprecated.
        m.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.JAVA_LANG_OBJECT,
                JsonTypeInfo.As.PROPERTY);
        return m;
    }
}
