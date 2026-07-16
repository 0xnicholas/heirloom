package com.heirloom.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class CoreModuleArchitectureTest {

    @Test
    void coreModule_shouldNotDependOnSpringBoot() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.heirloom.core");

        ArchRule rule = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "org.hibernate..",
                "jakarta.persistence.."
            );

        rule.check(classes);
    }

    @Test
    void coreModule_shouldNotDependOnConnectors() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.heirloom.core");

        ArchRule rule = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.heirloom.connector..");

        rule.check(classes);
    }

    @Test
    void coreModule_shouldNotDependOnServer() {
        JavaClasses classes = new ClassFileImporter()
            .importPackages("com.heirloom.core");

        ArchRule rule = noClasses()
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.heirloom.metadata..",
                               "com.heirloom.query..",
                               "com.heirloom.service..",
                               "com.heirloom.web..");

        rule.check(classes);
    }
}
