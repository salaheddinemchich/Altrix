package com.altrix.orchestrator.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * CQRS naming discipline (#165).
 * <p>
 * Commands change state and are named {@code *UseCase} or {@code *Command}.
 * Queries read state and are named {@code *Query} or {@code *UseCase}.
 * <p>
 * The rule here enforces that the driving port interfaces in
 * {@code domain.port.in} follow the convention:
 * <ul>
 *   <li>Write-side (commands): {@code *UseCase}</li>
 *   <li>Read-side (queries):  {@code *Query} or {@code *UseCase}</li>
 *   <li>Value objects passed over the port boundary: {@code *Command} or {@code *View} or {@code *Summary}</li>
 * </ul>
 */
@AnalyzeClasses(
        packages = "com.altrix.orchestrator",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CqrsRuleTest {

    @ArchTest
    static final ArchRule driving_port_interfaces_follow_use_case_naming =
            classes().that().resideInAPackage("..domain.port.in..")
                    .and().areInterfaces()
                    .should().haveSimpleNameEndingWith("UseCase")
                    .because("driving port interfaces represent a single use-case and must be named *UseCase");

    @ArchTest
    static final ArchRule commands_and_views_are_not_interfaces =
            classes().that().resideInAPackage("..domain.port.in..")
                    .and().haveSimpleNameEndingWith("Command")
                    .should().notBeInterfaces()
                    .because("command objects carry data and must be concrete records or classes, not interfaces");
}
