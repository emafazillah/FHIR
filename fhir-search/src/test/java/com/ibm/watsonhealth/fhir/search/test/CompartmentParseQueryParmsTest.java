/**
 * (C) Copyright IBM Corp. 2016,2017,2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.search.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ibm.watsonhealth.fhir.model.CommunicationRequest;
import com.ibm.watsonhealth.fhir.model.Condition;
import com.ibm.watsonhealth.fhir.model.Device;
import com.ibm.watsonhealth.fhir.model.Observation;
import com.ibm.watsonhealth.fhir.model.Resource;
import com.ibm.watsonhealth.fhir.search.Parameter;
import com.ibm.watsonhealth.fhir.search.Parameter.Type;
import com.ibm.watsonhealth.fhir.search.context.FHIRSearchContext;
import com.ibm.watsonhealth.fhir.search.exception.FHIRSearchException;
import com.ibm.watsonhealth.fhir.search.util.SearchUtil;

/**
 * This JUNIT test class contains methods that test the parsing of compartment related search data in the SearchUtil class.
 * @author markd
 *
 */
public class CompartmentParseQueryParmsTest {
	
	/**
	 * This method tests parsing compartment related query parms, passing an invalid compartment.
	 * @throws Exception
	 */
    @Test(expected = FHIRSearchException.class) 
    public void testInvalidComparmentName() throws Exception{
        Map<String, List<String>> queryParameters = new HashMap<>();
    	SearchUtil.parseQueryParameters("bogusCompartmentName", "1", Observation.class, queryParameters);
    }
    
    /**
	 * This method tests parsing compartment related query parms, passing an invalid resource type for the compartment.
	 * @throws Exception
	 */
    @Test(expected = FHIRSearchException.class) 
    public void testInvalidResource() throws Exception{
        Map<String, List<String>> queryParameters = new HashMap<>();
    	SearchUtil.parseQueryParameters("Patient", "1", Device.class, queryParameters);
    }
    
    /**
     * This method tests parsing compartment related query parms. 
     * Based on the compartment and resource type, a single inclusion criterion is expected to be 
     * returned by SearchUtil.parseQueryParameters(). 
     * @throws Exception
     */
    @Test 
    public void testSingleInclusionCriteria() throws Exception{
        Map<String, List<String>> queryParameters = new HashMap<>();
        String compartmentName = "Patient";
        String compartmentLogicalId = "11";
        Class<? extends Resource> resourceType = Condition.class;
    	FHIRSearchContext context = SearchUtil.parseQueryParameters(compartmentName, compartmentLogicalId, resourceType, queryParameters);
    	
    	assertNotNull(context);
    	assertNotNull(context.getSearchParameters());
    	assertEquals(1, context.getSearchParameters().size());
    	Parameter parm1 = context.getSearchParameters().get(0);
    	assertEquals("patient",parm1.getName());
    	assertNull(parm1.getNextParameter());
    	assertEquals(Type.REFERENCE, parm1.getType());
    	assertEquals(1, parm1.getValues().size());
    	assertEquals(compartmentName + "/" + compartmentLogicalId, parm1.getValues().get(0).getValueString());
    }
    
    /**
     * This method tests parsing compartment related query parms. 
     * Based on the compartment and resource type, multiple inclusion criteria is expected to be 
     * returned by SearchUtil.parseQueryParameters(). 
     * @throws Exception
     */
    @Test 
    public void testMultiInclusionCriteria() throws Exception{
        Map<String, List<String>> queryParameters = new HashMap<>();
        String compartmentName = "RelatedPerson";
        String compartmentLogicalId = "22";
        Class<? extends Resource> resourceType = CommunicationRequest.class;
    	FHIRSearchContext context = SearchUtil.parseQueryParameters(compartmentName, compartmentLogicalId, resourceType, queryParameters);
    	
    	assertNotNull(context);
    	assertNotNull(context.getSearchParameters());
    	assertEquals(1, context.getSearchParameters().size());
    	
    	Parameter searchParm = context.getSearchParameters().get(0);
    	int parmCount = 0;
    	while (searchParm != null) {
    		parmCount++;
    		assertTrue((searchParm.getName().equals("recipient") ||
    				   searchParm.getName().equals("requester") ||
    				   searchParm.getName().equals("sender")));
    		assertEquals(Type.REFERENCE, searchParm.getType());
    		assertTrue(searchParm.isInclusionCriteria());
    		assertFalse(searchParm.isChained());
        	assertEquals(1, searchParm.getValues().size());
        	assertEquals(compartmentName + "/" + compartmentLogicalId, searchParm.getValues().get(0).getValueString());
        	searchParm = searchParm.getNextParameter();
        }
    	assertEquals(3, parmCount);
    }
    
    /**
     * This method tests parsing compartment related query parms together with non-compartment related query parms.. 
     * Based on the compartment and resource type, multiple inclusion criteria is expected to be 
     * returned by SearchUtil.parseQueryParameters(). 
     * @throws Exception
     */
    @Test 
    public void testComparmentWithQueryParms() throws Exception{
        Map<String, List<String>> queryParameters = new HashMap<>();
        String compartmentName = "Patient";
        String compartmentLogicalId = "33";
        Class<? extends Resource> resourceType = Observation.class;
        queryParameters.put("category", Collections.singletonList("vital-signs"));
        queryParameters.put("value-quantity", Collections.singletonList("eq185|http://unitsofmeasure.org|[lb_av]"));
    	FHIRSearchContext context = SearchUtil.parseQueryParameters(compartmentName, compartmentLogicalId, resourceType, queryParameters);
    	
    	assertNotNull(context);
    	assertNotNull(context.getSearchParameters());
    	assertEquals(3, context.getSearchParameters().size());
    	
    	// Validate compartment related search parms.
    	Parameter searchParm = context.getSearchParameters().get(0);
    	int parmCount = 0;
    	while (searchParm != null) {
    		parmCount++;
    		assertTrue((searchParm.getName().equals("performer") ||
    				   searchParm.getName().equals("subject")));
    		assertEquals(Type.REFERENCE, searchParm.getType());
    		assertTrue(searchParm.isInclusionCriteria());
    		assertFalse(searchParm.isChained());
        	assertEquals(1, searchParm.getValues().size());
        	assertEquals(compartmentName + "/" + compartmentLogicalId, searchParm.getValues().get(0).getValueString());
        	searchParm = searchParm.getNextParameter();
        }
    	assertEquals(2, parmCount);
    	
    	// Validate non-compartment related search parms.
    	for (int i = 1; i < 3; i++) {
    		searchParm = context.getSearchParameters().get(i);
    		assertTrue((searchParm.getName().equals("category") ||	
    				    searchParm.getName().equals("value-quantity")));
    		assertNotNull(searchParm.getValues());
    		assertEquals(1, searchParm.getValues().size());
    	}
    }
    
    /**
     * This method is not meant to be run as part of the normal execution of this test class.
     * It's special purpose is to print the contents of SearchUtil.compartmentMap. 
     * To execute this method, un-comment it and make method buildCompartmentMap() public.
     */
    /* @Test 
    public void testLoadCompartmentMap() {
    	Map<String, Map<String, List<String>>> compartmentMap = SearchUtil.buildCompartmentMap();
    	for (String compartment : compartmentMap.keySet()) {
    		System.out.println("Compartment: " + compartment);
    		Map<String, List<String>> map = compartmentMap.get(compartment);
    		for (String key : map.keySet()) {
    			List<String> inclusionCriteria = map.get(key);
    			System.out.println("    key: " + key + ", inclusionCriteria: " + inclusionCriteria);
    		}
    		
    	}
    } */
    
}