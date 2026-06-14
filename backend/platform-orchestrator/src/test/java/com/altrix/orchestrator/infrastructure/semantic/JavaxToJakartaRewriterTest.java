package com.altrix.orchestrator.infrastructure.semantic;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JavaxToJakartaRewriterTest {

    private final JavaxToJakartaRewriter rewriter = new JavaxToJakartaRewriter();

    private String rewriteOne(String src) {
        return rewriter.rewrite(Map.of("p/S.java", src)).rewrittenFiles().get("p/S.java");
    }

    // ── the real bug ────────────────────────────────────────────────────────

    @Test
    void rewritesEnterpriseAndInjectImports() {
        String src = """
                package p;
                import javax.enterprise.context.ApplicationScoped;
                import javax.inject.Named;
                @ApplicationScoped @Named
                public class S {}""";
        String out = rewriteOne(src);
        assertThat(out).contains("import jakarta.enterprise.context.ApplicationScoped;");
        assertThat(out).contains("import jakarta.inject.Named;");
        assertThat(out).doesNotContain("javax.enterprise");
        assertThat(out).doesNotContain("javax.inject");
    }

    @Test
    void rewritesEjbPersistenceJaxrsValidationServlet() {
        String src = """
                package p;
                import javax.ejb.Singleton;
                import javax.persistence.Entity;
                import javax.ws.rs.GET;
                import javax.validation.constraints.NotNull;
                import javax.servlet.http.HttpServlet;
                public class S {}""";
        String out = rewriteOne(src);
        assertThat(out).contains("jakarta.ejb.Singleton");
        assertThat(out).contains("jakarta.persistence.Entity");
        assertThat(out).contains("jakarta.ws.rs.GET");
        assertThat(out).contains("jakarta.validation.constraints.NotNull");
        assertThat(out).contains("jakarta.servlet.http.HttpServlet");
        assertThat(out).doesNotContain("javax.");
    }

    @Test
    void rewritesFullyQualifiedUsagesNotJustImports() {
        String src = "package p;\npublic class S { javax.persistence.EntityManager em; }";
        String out = rewriteOne(src);
        assertThat(out).contains("jakarta.persistence.EntityManager");
        assertThat(out).doesNotContain("javax.persistence");
    }

    // ── JDK packages that must NOT be touched ───────────────────────────────

    @Test
    void leavesJdkJavaxPackagesUntouched() {
        String src = """
                package p;
                import javax.crypto.Cipher;
                import javax.sql.DataSource;
                import javax.naming.InitialContext;
                import javax.net.ssl.SSLSocket;
                import javax.xml.parsers.DocumentBuilder;
                import javax.xml.transform.Transformer;
                import javax.transaction.xa.XAResource;
                import javax.annotation.processing.Generated;
                import javax.management.MBeanServer;
                public class S {}""";
        String out = rewriteOne(src);
        // Every one of these stayed on javax — none moved to jakarta.
        assertThat(out).contains("javax.crypto.Cipher");
        assertThat(out).contains("javax.sql.DataSource");
        assertThat(out).contains("javax.naming.InitialContext");
        assertThat(out).contains("javax.net.ssl.SSLSocket");
        assertThat(out).contains("javax.xml.parsers.DocumentBuilder");
        assertThat(out).contains("javax.xml.transform.Transformer");
        assertThat(out).contains("javax.transaction.xa.XAResource");
        assertThat(out).contains("javax.annotation.processing.Generated");
        assertThat(out).contains("javax.management.MBeanServer");
        assertThat(out).doesNotContain("jakarta.");
    }

    @Test
    void movesEeSubsetsOfOtherwiseJdkNamespaces() {
        // javax.xml.bind / javax.transaction (non-xa) / javax.annotation (non-processing) DID move.
        String src = """
                package p;
                import javax.xml.bind.JAXBContext;
                import javax.transaction.Transactional;
                import javax.annotation.PostConstruct;
                public class S {}""";
        String out = rewriteOne(src);
        assertThat(out).contains("jakarta.xml.bind.JAXBContext");
        assertThat(out).contains("jakarta.transaction.Transactional");
        assertThat(out).contains("jakarta.annotation.PostConstruct");
    }

    @Test
    void isIdempotent() {
        String src = "package p;\nimport javax.inject.Inject;\npublic class S {}";
        String once = rewriteOne(src);
        String twice = rewriteOne(once);
        assertThat(twice).isEqualTo(once);
    }

    // ── gating ──────────────────────────────────────────────────────────────

    @Test
    void targetsJakarta_viaPomDependency() {
        String pom = "<project><dependencies><dependency><groupId>jakarta.platform</groupId>"
                + "<artifactId>jakarta.jakartaee-api</artifactId></dependency></dependencies></project>";
        assertThat(rewriter.targetsJakarta(Map.of(), pom, null)).isTrue();
    }

    @Test
    void targetsJakarta_viaExistingJakartaImport() {
        var files = Map.of("p/A.java", "package p;\nimport jakarta.inject.Inject;\npublic class A {}");
        assertThat(rewriter.targetsJakarta(files, null, null)).isTrue();
    }

    @Test
    void targetsJakarta_viaFrameworkLabel() {
        assertThat(rewriter.targetsJakarta(Map.of(), null, "Jakarta EE 10")).isTrue();
    }

    @Test
    void doesNotTargetJakarta_forLegacyJavaEeProject() {
        var files = Map.of("p/A.java", "package p;\nimport javax.ejb.Stateless;\npublic class A {}");
        String pom = "<project><dependencies><dependency><groupId>javax</groupId>"
                + "<artifactId>javaee-api</artifactId></dependency></dependencies></project>";
        assertThat(rewriter.targetsJakarta(files, pom, "Java EE 8")).isFalse();
    }
}
