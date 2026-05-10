package com.altrix.orchestrator.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces hexagonal architecture layer rules (#164).
 * <p>
 * Layer isolation invariants:
 * <ul>
 *   <li>Domain must not depend on Spring, JPA, or any adapter.</li>
 *   <li>Inbound adapters must not reach directly into the persistence layer.</li>
 *   <li>Domain services must only depend on domain ports, never on adapters.</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.altrix.orchestrator",
        importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    // ── Domain: no Spring ────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("domain layer must be framework-free");

    // ── Domain: no JPA ───────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule domain_must_not_depend_on_jpa =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("jakarta.persistence..")
                    .because("domain layer must be persistence-free");

    // ── Domain: no adapters ───────────────────────────────────────────────────

    @ArchTest
    static final ArchRule domain_must_not_depend_on_adapters =
            noClasses().that().resideInAPackage("..domain.service..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..adapter..")
                    .because("domain services must only depend on domain ports, never on adapters");

    // ── Domain: no infrastructure ──────────────────────────────────────────────

    @ArchTest
    static final ArchRule domain_must_not_depend_on_infrastructure =
            noClasses().that().resideInAPackage("..domain.service..")
                    .or().resideInAPackage("..domain.port..")
                    .or().resideInAPackage("..domain.model..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..")
                    .because("domain layer must not depend on Spring @Configuration classes");

    // ── Domain: no Jackson ────────────────────────────────────────────────────

    @ArchTest
    static final ArchRule domain_must_not_depend_on_jackson =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.fasterxml.jackson..")
                    .because("domain layer must not depend on serialisation frameworks");

    // ── Inbound adapters must not reach into persistence directly ─────────────

    @ArchTest
    static final ArchRule inbound_adapters_must_not_access_persistence_directly =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..adapter.out.persistence..")
                    .because("REST/Kafka adapters must go through domain use-cases, not JPA repositories");
}
