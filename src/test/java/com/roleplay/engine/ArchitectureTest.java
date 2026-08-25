package com.roleplay.engine;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guards the dependencies that keep spatial rules independent from web adapters.
 * Add a narrow rule here before extracting a new module; do not use this test to
 * force a speculative package rewrite.
 */
@AnalyzeClasses(packages = "com.roleplay.engine", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule spatialRulesDoNotDependOnWebControllers = noClasses()
            .that().resideInAnyPackage(
                    "..simulation.track..",
                    "..simulation.movement..",
                    "..simulation.map..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..");

    @ArchTest
    static final ArchRule coreModelDoesNotDependOnWebControllers = noClasses()
            .that().resideInAnyPackage("..core..", "..agent..")
            .should().dependOnClassesThat().resideInAnyPackage("..controller..");
}
