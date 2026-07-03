package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.infrastructure.config.KafkaMigrationKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Deterministic, no-AI safety net for the Pub/Sub → Kafka dependency swap
 * in {@code pom.xml}.
 *
 * <p>{@code CoreMigratorAgent}'s per-file LLM rewrite of pom.xml can fail
 * its structural sanity check (the model's XML doesn't parse, and the
 * em-dash-comment auto-repair doesn't fix it) and revert to the ORIGINAL
 * pom.xml — which still declares the old GCP Pub/Sub starter and never
 * gains {@code spring-kafka} — even when every Java file was correctly
 * migrated to Kafka APIs.  {@code PubSubLeakValidator} flags the resulting
 * stale dependency as a {@code GOOGLE_DEPENDENCY} violation, but
 * {@code PubSubLeakRepairer} cannot fix it: its repair gate only accepts
 * output containing a class/interface/enum/record declaration, which a
 * pom.xml "repair" never has, so the fix is silently dropped.
 *
 * <p>This component closes that gap with pure DOM manipulation — no LLM
 * call, so it can't hallucinate or produce unparseable XML:
 * <ol>
 *   <li>remove any {@code <dependency>} whose artifactId matches a known
 *       GCP/Pub-Sub fragment ({@link KafkaMigrationKnowledgeBase#allForbiddenDependencyArtifacts()});</li>
 *   <li>add whichever Kafka dependency the FINAL migrated Java files
 *       actually import but pom.xml is missing, via
 *       {@link KafkaMigrationKnowledgeBase#dependencyForClass(String)}.</li>
 * </ol>
 *
 * <p>If pom.xml doesn't parse, it's left untouched and the reason logged —
 * never worse than what {@code CoreMigratorAgent}'s own structural check
 * already accepted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PomDependencyReconciler {

    private static final Pattern IMPORT_LINE =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    /**
     * {@link KafkaMigrationKnowledgeBase#dependencyForClass(String)} returns
     * version-less {@code group:artifact} coordinates by design (its own
     * javadoc: "version policy is the pom's concern") — fine for its other
     * two consumers, which only ever read the coordinate, but fatal here:
     * this class WRITES a real {@code <dependency>} element, and Maven
     * refuses to even read a project model that declares one without a
     * {@code <version>} ("'dependencies.dependency.version' ... is
     * missing"), aborting before compilation gets a chance to run.
     *
     * <p>Every version below is already proven to resolve in this exact
     * production path: {@code kafka-clients} matches the pin used by the
     * hand-migrated {@code test-altrix-kafka} reference fixture;
     * {@code spring-kafka} and {@code spring-context} match the versions
     * the LLM itself already writes elsewhere in a Spring-Kafka-hybrid
     * target's generated pom.xml. A coordinate with no entry here is
     * deliberately left un-added (see {@link #addMissingDependencies}) —
     * an unresolved import is a normal, diagnosable compile error; an
     * unparseable pom.xml is not.
     */
    private static final Map<String, String> FALLBACK_VERSIONS = Map.ofEntries(
            entry("org.apache.kafka:kafka-clients", "3.9.0"),
            entry("org.springframework.kafka:spring-kafka", "3.1.6"),
            entry("org.springframework:spring-context", "6.1.13")
    );

    private final KafkaMigrationKnowledgeBase knowledgeBase;

    /**
     * @param pomXml    current pom.xml content — possibly already correct,
     *                  possibly the untouched pre-migration original.
     * @param javaFiles path → content of every migrated Java file in the
     *                  final artifact.
     * @return reconciled pom.xml, or {@code pomXml} unchanged when it
     *         doesn't parse or there's nothing to add/remove.
     */
    public String reconcile(String pomXml, Map<String, String> javaFiles) {
        if (pomXml == null || pomXml.isBlank()) return pomXml;

        Document doc = parseOrNull(pomXml);
        if (doc == null) {
            log.warn("[PomDependencyReconciler] pom.xml does not parse — skipping reconciliation");
            return pomXml;
        }

        boolean changed = removeForbiddenDependencies(doc);
        changed |= addMissingDependencies(doc, collectRequiredCoordinates(javaFiles));
        if (!changed) return pomXml;

        try {
            String serialized = serialize(doc);
            log.info("[PomDependencyReconciler] reconciled pom.xml dependencies deterministically");
            return serialized;
        } catch (Exception e) {
            log.warn("[PomDependencyReconciler] failed to serialise reconciled pom.xml — keeping prior content: {}",
                    e.getMessage());
            return pomXml;
        }
    }

    private Set<String> collectRequiredCoordinates(Map<String, String> javaFiles) {
        Set<String> coordinates = new LinkedHashSet<>();
        if (javaFiles == null) return coordinates;
        for (String content : javaFiles.values()) {
            if (content == null) continue;
            Matcher m = IMPORT_LINE.matcher(content);
            while (m.find()) {
                knowledgeBase.dependencyForClass(m.group(1)).ifPresent(coordinates::add);
            }
        }
        return coordinates;
    }

    private boolean removeForbiddenDependencies(Document doc) {
        Set<String> forbidden = knowledgeBase.allForbiddenDependencyArtifacts();
        if (forbidden.isEmpty()) return false;

        boolean changed = false;
        NodeList deps = doc.getElementsByTagName("dependency");
        // Iterate in reverse — removing nodes mutates the live NodeList.
        for (int i = deps.getLength() - 1; i >= 0; i--) {
            Element dependency = (Element) deps.item(i);
            String artifactId = textOfChild(dependency, "artifactId");
            if (artifactId == null) continue;
            String lower = artifactId.toLowerCase();
            if (forbidden.stream().map(String::toLowerCase).anyMatch(lower::contains)) {
                Node parent = dependency.getParentNode();
                if (parent != null) {
                    parent.removeChild(dependency);
                    changed = true;
                    log.info("[PomDependencyReconciler] removed forbidden dependency '{}'", artifactId);
                }
            }
        }
        return changed;
    }

    private boolean addMissingDependencies(Document doc, Set<String> requiredCoordinates) {
        if (requiredCoordinates.isEmpty()) return false;

        Element project = doc.getDocumentElement();
        Element dependencies = firstChildElement(project, "dependencies");
        if (dependencies == null) {
            dependencies = doc.createElement("dependencies");
            project.appendChild(dependencies);
        }

        Set<String> present = new LinkedHashSet<>();
        NodeList existing = dependencies.getElementsByTagName("dependency");
        for (int i = 0; i < existing.getLength(); i++) {
            Element dep = (Element) existing.item(i);
            present.add(textOfChild(dep, "groupId") + ":" + textOfChild(dep, "artifactId"));
        }

        boolean changed = false;
        for (String coordinate : requiredCoordinates) {
            if (present.contains(coordinate)) continue;
            String[] parts = coordinate.split(":", 2);
            if (parts.length != 2) continue;

            String version = FALLBACK_VERSIONS.get(coordinate);
            if (version == null) {
                log.warn("[PomDependencyReconciler] skipping '{}' — no known fallback version, " +
                        "adding it without one would make pom.xml unparseable by Maven", coordinate);
                continue;
            }

            Element dependency = doc.createElement("dependency");
            dependency.appendChild(textElement(doc, "groupId", parts[0]));
            dependency.appendChild(textElement(doc, "artifactId", parts[1]));
            dependency.appendChild(textElement(doc, "version", version));
            dependencies.appendChild(dependency);
            changed = true;
            log.info("[PomDependencyReconciler] added missing dependency '{}:{}'", coordinate, version);
        }
        return changed;
    }

    // ── DOM helpers — same XXE-safe parse/serialise pattern as PomSanitizer ──

    private static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element el && tagName.equals(el.getTagName())) return el;
        }
        return null;
    }

    private static String textOfChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        if (children.getLength() == 0) return null;
        String text = children.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private static Element textElement(Document doc, String tagName, String text) {
        Element el = doc.createElement(tagName);
        el.setTextContent(text);
        return el;
    }

    private static Document parseOrNull(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setValidating(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        // Defence-in-depth — no external resolution while serialising.
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
