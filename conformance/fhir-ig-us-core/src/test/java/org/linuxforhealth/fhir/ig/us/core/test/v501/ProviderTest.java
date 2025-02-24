/*
 * (C) Copyright IBM Corp. 2021
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.linuxforhealth.fhir.ig.us.core.test.v501;

import static org.linuxforhealth.fhir.path.util.FHIRPathUtil.compile;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import org.linuxforhealth.fhir.ig.us.core.USCore501ResourceProvider;
import org.linuxforhealth.fhir.model.annotation.Constraint;
import org.linuxforhealth.fhir.model.resource.OperationOutcome.Issue;
import org.linuxforhealth.fhir.model.resource.Resource;
import org.linuxforhealth.fhir.model.resource.StructureDefinition;
import org.linuxforhealth.fhir.model.type.Extension;
import org.linuxforhealth.fhir.model.type.code.IssueSeverity;
import org.linuxforhealth.fhir.model.util.ModelSupport;
import org.linuxforhealth.fhir.profile.ProfileSupport;
import org.linuxforhealth.fhir.registry.FHIRRegistry;
import org.linuxforhealth.fhir.registry.resource.FHIRRegistryResource;
import org.linuxforhealth.fhir.registry.spi.FHIRRegistryResourceProvider;
import org.linuxforhealth.fhir.validation.FHIRValidator;

/**
 * Tests that the Provider executes properly.
 */
public class ProviderTest {
    @Test
    public void testRegistry() {
        StructureDefinition definition = FHIRRegistry.getInstance()
                .getResource("http://hl7.org/fhir/us/core/StructureDefinition/pediatric-bmi-for-age", StructureDefinition.class);
        assertNotNull(definition);
        assertEquals(definition.getVersion().getValue(), "5.0.1");
    }

    @Test
    public void testUSCoreResourceProvider() {
        FHIRRegistryResourceProvider provider = new USCore501ResourceProvider();
        assertEquals(provider.getRegistryResources().size(), 194);
    }

    @Test
    public void testValidateResources() throws Exception {
        FHIRRegistryResourceProvider provider = new USCore501ResourceProvider();

        List<Exception> exceptions = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();

        FHIRValidator validator = FHIRValidator.validator();

        for (FHIRRegistryResource registryResource : provider.getRegistryResources()) {
            try {
                Resource resource = registryResource.getResource();
                issues.addAll(validator.validate(resource));
            } catch (Exception e) {
                exceptions.add(e);
            }
        }

        assertEquals(exceptions.size(), 0);
        assertFalse(issues.stream().anyMatch(issue -> IssueSeverity.ERROR.equals(issue.getSeverity())));
    }

    @Test
    public static void testConstraintGenerator() throws Exception {
        FHIRRegistryResourceProvider provider = new USCore501ResourceProvider();
        for (FHIRRegistryResource registryResource : provider.getRegistryResources()) {
            if (StructureDefinition.class.equals(registryResource.getResourceType())) {
                assertEquals(registryResource.getVersion().toString(), "5.0.1");
                String url = registryResource.getUrl() + "|5.0.1";
                System.out.println(url);
                Class<?> type = ModelSupport.isResourceType(registryResource.getType()) ? ModelSupport.getResourceType(registryResource.getType()) : Extension.class;
                for (Constraint constraint : ProfileSupport.getConstraints(url, type)) {
                    System.out.println("    " + constraint);
                    if (!Constraint.LOCATION_BASE.equals(constraint.location())) {
                        compile(constraint.location());
                    }
                    compile(constraint.expression());
                }
            }
        }
    }
}
