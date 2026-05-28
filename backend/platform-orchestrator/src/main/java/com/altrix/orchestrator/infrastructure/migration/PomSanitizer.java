package com.altrix.orchestrator.infrastructure.migration;

import com.altrix.orchestrator.infrastructure.config.MigrationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

/**
 * Post-processes the migrated {@code pom.xml} to strip hallucinated
 * dependencies the AI may have added that were not in the original file.
 *
 * <p>Real example caught in production: the model saw an unused
 * {@code <hibernate.version>} property in the original pom and inserted a
 * {@code <dependency>org.hibernate:hibernate-entitymanager</dependency>}
 * that doesn't even exist in Hibernate 6, breaking the Maven dependency
 * resolution before any source compile could run.
 *
 * <p>Allowed additions (typically the Kafka client artifacts the migration
 * legitimately needs) come from {@link MigrationConfig.Pom#allowedAddedArtifacts()}
 * — never hardcoded here.
 *
 * <p>Both reads use the same XXE-safe parser as the rest of the codebase
 * ({@code disallow-doctype-decl} + external entities off).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PomSanitizer {

    private final MigrationConfig migrationConfig;

    /**
     * Returns the migrated pom with any unexpected new {@code <dependency>}
     * blocks removed.  When the migrated pom can't be parsed, returns it
     * unchanged — the caller's structural-validity gate will reject it on
     * its own.
     *
     * @param originalPom the pom.xml content as it was in the user's upload.
     * @param migratedPom the pom.xml content the migrator produced.
     */
    public String stripHallucinatedDependencies(String originalPom, String migratedPom) {
        if (originalPom == null || migratedPom == null) return migratedPom;
        Document origDoc = parseOrNull(originalPom);
        Document migDoc  = parseOrNull(migratedPom);
        if (origDoc == null || migDoc == null) return migratedPom;

        Set<String> originalArtifacts = collectArtifactIds(origDoc);
        Set<String> allowed = new HashSet<>(migrationConfig.pom().allowedAddedArtifacts());

        NodeList deps = migDoc.getElementsByTagName("dependency");
        int removed = 0;
        // Iterate in reverse — we mutate the live NodeList.
        for (int i = deps.getLength() - 1; i >= 0; i--) {
            Element dep = (Element) deps.item(i);
            String artifactId = textOfChild(dep, "artifactId");
            if (artifactId == null) continue;
            if (originalArtifacts.contains(artifactId) || allowed.contains(artifactId)) continue;
            log.warn("[PomSanitizer] removing hallucinated dependency '{}' from migrated pom", artifactId);
            Node parent = dep.getParentNode();
            if (parent != null) {
                parent.removeChild(dep);
                removed++;
            }
        }
        if (removed == 0) return migratedPom;
        return serialise(migDoc);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Document parseOrNull(String content) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setValidating(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(content)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> collectArtifactIds(Document doc) {
        Set<String> ids = new HashSet<>();
        NodeList list = doc.getElementsByTagName("dependency");
        for (int i = 0; i < list.getLength(); i++) {
            Element dep = (Element) list.item(i);
            String aid = textOfChild(dep, "artifactId");
            if (aid != null && !aid.isBlank()) ids.add(aid);
        }
        return ids;
    }

    private static String textOfChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        if (children.getLength() == 0) return null;
        String text = children.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private static String serialise(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            // Defence-in-depth — no external resolution while serialising.
            tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise sanitised pom.xml", e);
        }
    }
}
