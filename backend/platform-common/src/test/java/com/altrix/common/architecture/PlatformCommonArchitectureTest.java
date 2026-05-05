package com.altrix.common.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Platform-common is a pure domain library — no Spring, no JPA, no framework
 * dependencies. This test prevents accidental framework imports (#164).
 */
@AnalyzeClasses(
        packages = "com.altrix.common",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PlatformCommonArchitectureTest {

    @ArchTest
    static final ArchRule must_not_depend_on_spring =
            noClasses().that().resideInAPackage("com.altrix.common..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("platform-common is a framework-free library shared by all services");

    @ArchTest
    static final ArchRule must_not_depend_on_jpa =
            noClasses().that().resideInAPackage("com.altrix.common..")
                    .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                    .because("platform-common must not carry JPA annotations");
}
