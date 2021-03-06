/*
 * #%L
 * Alfresco Remote API
 * %%
 * Copyright (C) 2005 - 2017 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.rest.api.tests;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.action.executer.AddFeaturesActionExecuter;
import org.alfresco.repo.action.executer.CheckInActionExecuter;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.rest.api.model.ActionDefinition;
import org.alfresco.rest.api.tests.client.Pair;
import org.alfresco.rest.api.tests.client.PublicApiClient;
import org.alfresco.rest.api.tests.client.PublicApiClient.ListResponse;
import org.alfresco.rest.api.tests.client.RequestContext;
import org.alfresco.rest.framework.resource.parameters.Paging;
import org.alfresco.service.cmr.action.ActionService;
import org.alfresco.service.cmr.action.ParameterizedItemDefinition;
import org.alfresco.service.cmr.coci.CheckOutCheckInService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.namespace.QName;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Comparator;
import java.util.EmptyStackException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestActions extends AbstractBaseApiTest
{
    private NodeService nodeService;
    private ActionService actionService;
    private PublicApiClient.Actions actions;
    private RepoService.TestNetwork account1;
    private Iterator<RepoService.TestNetwork> accountsIt;
    private Iterator<String> account1PersonIt;
    private final static Map<String, String> emptyParams = Collections.EMPTY_MAP;
    private final static Log logger = LogFactory.getLog(TestActions.class);

    @Before
    public void setUp() throws Exception
    {
        nodeService = applicationContext.getBean("NodeService", NodeService.class);
        actionService = applicationContext.getBean("ActionService", ActionService.class);
        actions = publicApiClient.actions();

        accountsIt = getTestFixture().getNetworksIt();
        account1 = accountsIt.next();
        account1PersonIt = account1.getPersonIds().iterator();
        
        // Capture authentication pre-test, so we can restore it again afterwards.
        AuthenticationUtil.pushAuthentication();
    }

    @After
    public void tearDown()
    {
        // Restore authentication to pre-test state.
        try
        {
            AuthenticationUtil.popAuthentication();
        }
        catch(EmptyStackException e)
        {
            // Nothing to do.
        }
    }
    
    @Override
    public String getScope()
    {
        return "public";
    }
    
    @Test
    public void canGetActionDefinitionsForNode() throws Exception
    {
        final String person1 = account1PersonIt.next();
        publicApiClient.setRequestContext(new RequestContext(account1.getId(), person1));
        
        // Get the actions available on the -my- node-ref alias
        {
            ListResponse<ActionDefinition> actionDefs = actions.getActionDefinitionsForNode("-my-", emptyParams, 200);

            assertNotNull("Action definition list should not be null", actionDefs);
            assertFalse("Action definition list should not be empty", actionDefs.getList().isEmpty());

            // Check defaults, given that no paging params were sent in the request
            assertEquals(Paging.DEFAULT_MAX_ITEMS, actionDefs.getPaging().getMaxItems().intValue());
            assertEquals(Paging.DEFAULT_SKIP_COUNT, actionDefs.getPaging().getSkipCount().intValue());

            // Check ActionDefinition fields
            List<ActionDefinition> actionDefinitions = actionDefs.getList().stream().
                    filter(ad -> ad.getName().equals("add-features")).collect(Collectors.toList());
            assertEquals(1, actionDefinitions.size());

            ActionDefinition action = actionDefinitions.get(0);
            assertEquals("add-features", action.getId());
            assertEquals("add-features", action.getName());
            assertEquals("Add aspect", action.getTitle());
            assertEquals("This will add an aspect to the matched item.", action.getDescription());
            // Applicable types
            assertEquals(0, action.getApplicableTypes().size());
            assertEquals(false, action.isTrackStatus());
            // Parameter definitions
            assertEquals(1, action.getParameterDefinitions().size());
            ActionDefinition.ParameterDefinition paramDefs = action.getParameterDefinitions().get(0);
            assertEquals(AddFeaturesActionExecuter.PARAM_ASPECT_NAME, paramDefs.getName());
            assertEquals("d:qname", paramDefs.getType());
            assertEquals(true, paramDefs.isMandatory());
            assertEquals("Aspect", paramDefs.getDisplayLabel());
            assertEquals(false, paramDefs.isMultiValued());
            assertEquals("ac-aspects", paramDefs.getParameterConstraintName());
        }

        AuthenticationUtil.setFullyAuthenticatedUser(person1);
        
        // Get the actions for a "checked out" node - there should be a "check-in" action present.
        // Inspect the fields, to make sure that they're all there. Especially applicableTypes, as
        // this isn't available on any of the actions that appear for the "-my-" alias in the test above.
        {   
            NodeRef nodeForCheckout = nodeService.createNode(
                    new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, getMyNodeId()),
                    ContentModel.ASSOC_CONTAINS,
                    QName.createQName("test", "test-node-checkedout"),
                    ContentModel.TYPE_CONTENT).getChildRef();
            CheckOutCheckInService coci = applicationContext.getBean("CheckOutCheckInService", CheckOutCheckInService.class);
            coci.checkout(nodeForCheckout);

            ListResponse<ActionDefinition> actionDefs =
                    actions.getActionDefinitionsForNode(nodeForCheckout.getId(), emptyParams, 200);

            List<ActionDefinition> actionDefinitions = actionDefs.getList().stream().
                    filter(ad -> ad.getName().equals("check-in")).collect(Collectors.toList());
            assertEquals(1, actionDefinitions.size());
            
            ActionDefinition action = actionDefinitions.get(0);
            assertEquals("check-in", action.getId());
            assertEquals("check-in", action.getName());
            assertEquals("Check in", action.getTitle());
            assertEquals("This will check in the matched content.", action.getDescription());
            // Applicable types
            assertEquals(1, action.getApplicableTypes().size());
            assertEquals("cm:content", action.getApplicableTypes().get(0));
            assertEquals(false, action.isTrackStatus());
            // Parameter definitions
            assertEquals(2, action.getParameterDefinitions().size());
            //    "description"
            ActionDefinition.ParameterDefinition paramDefs = action.getParameterDefinitions().get(0);
            assertEquals(CheckInActionExecuter.PARAM_DESCRIPTION, paramDefs.getName());
            assertEquals("d:text", paramDefs.getType());
            assertEquals(false, paramDefs.isMandatory());
            assertEquals("Description", paramDefs.getDisplayLabel());
            assertEquals(false, paramDefs.isMultiValued());
            assertEquals(null, paramDefs.getParameterConstraintName());
            //    "minorChange"
            paramDefs = action.getParameterDefinitions().get(1);
            assertEquals(CheckInActionExecuter.PARAM_MINOR_CHANGE, paramDefs.getName());
            assertEquals("d:boolean", paramDefs.getType());
            assertEquals(false, paramDefs.isMandatory());
            assertEquals("Minor change", paramDefs.getDisplayLabel());
            assertEquals(false, paramDefs.isMultiValued());
            assertEquals(null, paramDefs.getParameterConstraintName());
        }

        String myNode = getMyNodeId();
        NodeRef validNode = nodeService.createNode(
                new NodeRef(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, myNode),
                ContentModel.ASSOC_CONTAINS,
                QName.createQName("test", "test-node"),
                ContentModel.TYPE_CONTENT).getChildRef();
        
        // Get the actions available using a specific node ID
        {
            
            ListResponse<ActionDefinition> actionDefs = actions.getActionDefinitionsForNode(validNode.getId(), emptyParams, 200);
            
            assertNotNull("Action definition list should not be null", actionDefs);
            assertFalse("Action definition list should not be empty", actionDefs.getList().isEmpty());
        }

        // Test paging
        {
            // Default sort order is by name ascending
            List<String> expectedNames =
                    actionService.getActionDefinitions(validNode).
                    stream().
                    sorted(Comparator.comparing(org.alfresco.service.cmr.action.ActionDefinition::getName)).
                    map(ParameterizedItemDefinition::getName).        
                    collect(Collectors.toList());
            
            // Retrieve all action defs using the REST API - then check that they match
            // the list retrieved directly from the ActionService.
            PublicApiClient.Paging paging = getPaging(0, Integer.MAX_VALUE);
            
            // Retrieve all the results, sorted, on one page
            ListResponse<ActionDefinition> actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, null), 200);
            
            // ActionService and the REST API return very different types, so mapping both lists
            // to Strings to make a simple comparison easy.
            List<String> actionNames = actionDefs.getList().stream().
                    map(ActionDefinition::getName).
                    collect(Collectors.toList());
            
            // Check the whole lists match
            assertEquals(expectedNames, actionNames);

            final int pageSize = 2;
            if (expectedNames.size() < ((pageSize * 2) + 1)) // need at least 3 pages worth
            {
                // By default there are plenty of actions available to the created node. If this
                // ceases to be the case in the future, this test should be modified to make sure
                // there are sufficient action definitions to be listed for the node.
                fail("Cannot perform useful paging tests - too few action definitions.");
            }
            else
            {
                // Page 1
                paging = getPaging(0, pageSize);
                actionDefs = actions.
                        getActionDefinitionsForNode(validNode.getId(), createParams(paging, null), 200);
                assertEquals(pageSize, actionDefs.getList().size());
                assertEquals(pageSize, (long) actionDefs.getPaging().getCount());
                assertEquals(expectedNames.size(), (int) actionDefs.getPaging().getTotalItems());
                assertTrue(actionDefs.getPaging().getHasMoreItems());

                // Page 2
                paging = getPaging(pageSize, pageSize, expectedNames.size(), expectedNames.size());
                actionDefs = actions.
                        getActionDefinitionsForNode(validNode.getId(), createParams(paging, null), 200);
                assertEquals(pageSize, actionDefs.getList().size());
                assertEquals(pageSize, (long) actionDefs.getPaging().getCount());
                assertEquals(expectedNames.size(), (int) actionDefs.getPaging().getTotalItems());
                assertTrue(actionDefs.getPaging().getHasMoreItems());
                
                // Get a 'page' consisting of just the last item, regardless of pageSize 
                paging = getPaging(expectedNames.size()-1, pageSize);
                actionDefs = actions.
                        getActionDefinitionsForNode(validNode.getId(), createParams(paging, null), 200);
                assertEquals(1, actionDefs.getList().size());
                assertEquals(1L, (long) actionDefs.getPaging().getCount());
                assertEquals(expectedNames.size(), (int) actionDefs.getPaging().getTotalItems());
                assertFalse(actionDefs.getPaging().getHasMoreItems());
            }
        }
        
        // Test sorting by title
        {
            // Retrieve all the actions directly using the ActionService and sort by title.
            List<Pair<String, String>> expectedActions =
                    actionService.getActionDefinitions(validNode).
                            stream().
                            sorted(Comparator.comparing(org.alfresco.service.cmr.action.ActionDefinition::getTitle)).
                            map(act -> new Pair<>(act.getName(), act.getTitle())).
                            collect(Collectors.toList());

            // Retrieve all action defs using the REST API - then check that they match
            // the list retrieved directly from the ActionService.
            PublicApiClient.Paging paging = getPaging(0, Integer.MAX_VALUE);

            // Retrieve all the results, sorted, on one page
            Map<String, String> orderBy = Collections.singletonMap("orderBy", "title");
            ListResponse<ActionDefinition> actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);
            
            List<Pair<String, String>> retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());

            // Check the whole lists match
            assertEquals(expectedActions, retrievedActions);

            // Again, by title, but with explicit ascending sort order
            orderBy = Collections.singletonMap("orderBy", "title asc");
            actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);

            retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());

            // Check the whole lists match
            assertEquals(expectedActions, retrievedActions);


            // Descending sort order
            orderBy = Collections.singletonMap("orderBy", "title desc");
            actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);

            retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());

            // Check the whole lists match
            Collections.reverse(expectedActions);
            assertEquals(expectedActions, retrievedActions);
            
            // Combine paging with sorting by title, descending.
            final int pageSize = 2;
            paging = getPaging(pageSize, pageSize);
            actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);

            retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());
            
            assertEquals(expectedActions.subList(pageSize, pageSize*2), retrievedActions);
        }

        // Test explicit sorting by name
        {
            // Retrieve all the actions directly using the ActionService and sort by name.
            List<Pair<String, String>> expectedActions =
                    actionService.getActionDefinitions(validNode).
                            stream().
                            sorted(Comparator.comparing(org.alfresco.service.cmr.action.ActionDefinition::getName)).
                            map(act -> new Pair<>(act.getName(), act.getTitle())).
                            collect(Collectors.toList());

            // Retrieve all action defs using the REST API - then check that they match
            // the list retrieved directly from the ActionService.
            PublicApiClient.Paging paging = getPaging(0, Integer.MAX_VALUE);

            // Retrieve all the results, sorted, on one page
            Map<String, String> orderBy = Collections.singletonMap("orderBy", "name");
            ListResponse<ActionDefinition> actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);

            List<Pair<String, String>> retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());

            // Check the whole lists match
            assertEquals(expectedActions, retrievedActions);

            // Again, by name, but with explicit ascending sort order
            orderBy = Collections.singletonMap("orderBy", "name asc");
            actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);

            retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());

            // Check the whole lists match
            assertEquals(expectedActions, retrievedActions);


            // Descending sort order
            orderBy = Collections.singletonMap("orderBy", "name desc");
            actionDefs = actions.
                    getActionDefinitionsForNode(validNode.getId(), createParams(paging, orderBy), 200);

            retrievedActions = actionDefs.getList().stream().
                    map(act -> new Pair<>(act.getName(), act.getTitle())).
                    collect(Collectors.toList());

            // Check the whole lists match
            Collections.reverse(expectedActions);
            assertEquals(expectedActions, retrievedActions);
        }
        
        // Badly formed request -> 400
        {
            PublicApiClient.Paging paging = getPaging(0, -1); // -1 is not acceptable
            actions.getActionDefinitionsForNode(validNode.getId(), createParams(paging, null), 400);
        }
        
        // Non-existent node ID
        {
            NodeRef nodeRef = new NodeRef(
                    StoreRef.STORE_REF_WORKSPACE_SPACESSTORE,
                    "750a2867-ecfa-478c-8343-fa0e39d27be3");
            assertFalse("Test pre-requisite: node must not exist", nodeService.exists(nodeRef));
            
            actions.getActionDefinitionsForNode(nodeRef.getId(), emptyParams, 404);
        }
        
        // Unauthorized -> 401
        {
            publicApiClient.setRequestContext(new RequestContext(account1.getId(), person1, "invalid-password"));
            actions.getActionDefinitionsForNode(validNode.getId(), emptyParams, 401);
        }
    }
}
