package com.altrix.orchestrator.infrastructure.semantic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corrects Kafka (de)serializer FQNs the migrator mis-files under Spring's
 * {@code org.springframework.kafka.support.serializer} package — a bug that
 * COMPILES (the string lives in {@code application.yml}) but crashes at
 * startup: Spring's producer/consumer factory tries to load the class and
 * throws {@code ClassNotFoundException} → {@code APPLICATION FAILED TO START}.
 *
 * <p>The String/Long/Integer/… (de)serializers live in
 * {@code org.apache.kafka.common.serialization}; only Spring's OWN
 * serializers ({@code JsonSerializer}, {@code DelegatingSerializer}, …)
 * belong under {@code org.springframework.kafka.support.serializer} — those
 * are deliberately NOT remapped.
 *
 * <p>Operates on config (yml/yaml/properties) AND java files via a curated
 * exact-FQN replacement, so it's safe: the wrong FQNs don't exist, so every
 * replacement is a strict correction.
 */
@Slf4j
@Component
public class MessagingConfigRepairer {

    private static final String WRONG = "org.springframework.kafka.support.serializer.";
    private static final String RIGHT = "org.apache.kafka.common.serialization.";

    /** Apache-native (de)serializers the model wrongly places in the Spring package. */
    private static final List<String> APACHE_NATIVE = List.of(
            "StringSerializer", "StringDeserializer",
            "LongSerializer", "LongDeserializer",
            "IntegerSerializer", "IntegerDeserializer",
            "ShortSerializer", "ShortDeserializer",
            "DoubleSerializer", "DoubleDeserializer",
            "FloatSerializer", "FloatDeserializer",
            "ByteArraySerializer", "ByteArrayDeserializer",
            "BytesSerializer", "BytesDeserializer",
            "UUIDSerializer", "UUIDDeserializer",
            "ByteBufferSerializer", "ByteBufferDeserializer");

    private static final Map<String, String> CORRECTIONS = buildCorrections();

    private static Map<String, String> buildCorrections() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : APACHE_NATIVE) m.put(WRONG + t, RIGHT + t);
        return m;
    }

    public Result repair(Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            return new Result(files == null ? Map.of() : files, List.of());
        }
        // Does any source consume with MANUAL ack (an `Acknowledgment` param)?
        // If so the listener container needs ack-mode: manual, else Spring
        // throws at the FIRST message ("No Acknowledgment available…").
        boolean usesManualAck = files.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getKey().toLowerCase().endsWith(".java"))
                .anyMatch(e -> e.getValue() != null && e.getValue().contains("Acknowledgment"));

        Map<String, String> out = new LinkedHashMap<>(files.size());
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            if (path == null || content == null || !isConfigOrJava(path)) {
                out.put(path, content);
                continue;
            }
            String fixed = content;
            for (Map.Entry<String, String> c : CORRECTIONS.entrySet()) {
                if (fixed.contains(c.getKey())) fixed = fixed.replace(c.getKey(), c.getValue());
            }
            if (usesManualAck) fixed = ensureManualAckMode(path, fixed);
            out.put(path, fixed);
            if (!fixed.equals(content)) changed.add(path);
        }
        if (!changed.isEmpty()) {
            log.info("[MessagingConfigRepairer] messaging-config corrections applied to {}", changed);
        }
        return new Result(out, changed);
    }

    /**
     * Ensures {@code spring.kafka.listener.ack-mode: manual} is present when a
     * manual-ack consumer exists.  Handles the dominant Spring Boot shapes:
     * a {@code spring:\n  kafka:} YAML block (2-space indent) and a
     * {@code .properties} file.  Leaves the config untouched if {@code ack-mode}
     * is already set, or if the shape isn't recognised (safe no-op).
     */
    private static String ensureManualAckMode(String path, String content) {
        if (content.contains("ack-mode")) return content;
        String p = path.toLowerCase();
        if (p.endsWith(".properties")) {
            if (!content.contains("spring.kafka")) return content;
            String nl = content.endsWith("\n") || content.isEmpty() ? "" : "\n";
            return content + nl + "spring.kafka.listener.ack-mode=manual\n";
        }
        // YAML: insert a listener block as the first child under `  kafka:`.
        if (p.endsWith(".yml") || p.endsWith(".yaml")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(\\s{2})kafka:\\s*$")
                    .matcher(content);
            if (m.find()) {
                String childIndent = m.group(1) + "  ";        // 4 spaces
                String insert = "\n" + childIndent + "listener:\n" + childIndent + "  ack-mode: manual";
                return content.substring(0, m.end()) + insert + content.substring(m.end());
            }
        }
        return content;
    }

    private static boolean isConfigOrJava(String path) {
        String p = path.toLowerCase();
        return p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties") || p.endsWith(".java");
    }

    public record Result(Map<String, String> repairedFiles, List<String> changedPaths)
            implements Serializable {
        public Result {
            repairedFiles = repairedFiles == null ? Map.of() : Map.copyOf(repairedFiles);
            changedPaths  = changedPaths  == null ? List.of() : List.copyOf(changedPaths);
        }
        public boolean changedAnything() { return !changedPaths.isEmpty(); }
    }
}
