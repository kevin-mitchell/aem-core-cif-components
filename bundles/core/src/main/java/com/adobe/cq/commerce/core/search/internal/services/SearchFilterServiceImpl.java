/*******************************************************************************
 *
 *    Copyright 2019 Adobe. All rights reserved.
 *    This file is licensed to you under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License. You may obtain a copy
 *    of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software distributed under
 *    the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
 *    OF ANY KIND, either express or implied. See the License for the specific language
 *    governing permissions and limitations under the License.
 *
 ******************************************************************************/

package com.adobe.cq.commerce.core.search.internal.services;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.commerce.core.components.client.MagentoGraphqlClient;
import com.adobe.cq.commerce.core.search.internal.converters.FilterAttributeMetadataConverter;
import com.adobe.cq.commerce.core.search.models.FilterAttributeMetadata;
import com.adobe.cq.commerce.core.search.services.FilterAttributeMetadataCache;
import com.adobe.cq.commerce.core.search.services.SearchFilterService;
import com.adobe.cq.commerce.graphql.client.GraphqlResponse;
import com.adobe.cq.commerce.magento.graphql.Attribute;
import com.adobe.cq.commerce.magento.graphql.AttributeInput;
import com.adobe.cq.commerce.magento.graphql.CustomAttributeMetadataQueryDefinition;
import com.adobe.cq.commerce.magento.graphql.Operations;
import com.adobe.cq.commerce.magento.graphql.Query;
import com.adobe.cq.commerce.magento.graphql.QueryQuery;
import com.adobe.cq.commerce.magento.graphql.gson.Error;
import com.adobe.cq.commerce.magento.graphql.introspection.FilterIntrospectionQuery;
import com.adobe.cq.commerce.magento.graphql.introspection.InputField;
import com.adobe.cq.commerce.magento.graphql.introspection.IntrospectionQuery;

@Component(service = SearchFilterService.class)
public class SearchFilterServiceImpl implements SearchFilterService {

    @Reference
    FilterAttributeMetadataCache filterAttributeMetadataCache;

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchFilterServiceImpl.class);

    @Override
    public List<FilterAttributeMetadata> retrieveCurrentlyAvailableCommerceFilters(final Resource resource) {
        return filterAttributeMetadataCache.getFilterAttributeMetadata().orElseGet(() -> {

            // First we query Magento for the required attribute and filter information
            MagentoGraphqlClient magentoIntrospectionGraphqlClient = MagentoGraphqlClient.create(resource, true);
            final List<InputField> availableFilters = fetchAvailableSearchFilters(magentoIntrospectionGraphqlClient);
            MagentoGraphqlClient magentoGraphqlClient = MagentoGraphqlClient.create(resource, false);
            final List<Attribute> attributes = fetchAttributeMetadata(magentoGraphqlClient, availableFilters);

            // Then we combine this data into a useful set of data usable by other systems
            FilterAttributeMetadataConverter converter = new FilterAttributeMetadataConverter(attributes);
            final List<FilterAttributeMetadata> filterAttributeMetadata = availableFilters.stream().map(converter).collect(Collectors
                .toList());

            // Finally we set the filter metadata back to the caching layer so it's available in the future without requring another set of
            // calls
            // to Magento's APIs
            filterAttributeMetadataCache.setFilterAttributeMetadata(filterAttributeMetadata);
            return filterAttributeMetadata;
        });
    }

    private List<Attribute> fetchAttributeMetadata(final MagentoGraphqlClient magentoGraphqlClient,
        final List<InputField> availableFilters) {

        List<AttributeInput> attributeInputs = availableFilters.stream().map(inputField -> {
            AttributeInput attributeInput = new AttributeInput();
            attributeInput.setAttributeCode(inputField.getName());
            attributeInput.setEntityType("4");
            return attributeInput;
        }).collect(Collectors.toList());

        CustomAttributeMetadataQueryDefinition queryArgs = attributeQuery -> attributeQuery
            .items(_queryBuilder -> _queryBuilder
                .attributeCode()
                .attributeType()
                .inputType());
        final QueryQuery attributeQuery = Operations.query(query -> query.customAttributeMetadata(attributeInputs, queryArgs));

        if (magentoGraphqlClient == null) {
            LOGGER.error("MagentoGraphQL client is null, unable to make query to fetch attribute metadata.");
            return new ArrayList<>();
        }

        final GraphqlResponse<Query, Error> response = magentoGraphqlClient.execute(
            attributeQuery.toString());

        // If there are errors we'll log them and return a safe but empty list
        if (response.getErrors() != null && response.getErrors().size() > 0) {
            response.getErrors().stream()
                .forEach(err -> LOGGER.error("An error has occurred: {} ({})", err.getMessage(), err.getCategory()));
            return new ArrayList<>();
        }

        return response.getData().getCustomAttributeMetadata().getItems();

    }

    /**
     * Fetches a list of available search filters from the commerce backend.
     *
     * @param magentoGraphqlClient client for making Magento GraphQL requests
     * @return key value pair of the attribute code or identifier and filter type for that attribute
     */
    private List<InputField> fetchAvailableSearchFilters(final MagentoGraphqlClient magentoGraphqlClient) {

        if (magentoGraphqlClient == null) {
            LOGGER.error("MagentoGraphQL client is null, unable to make introspection call to fetch available filter attributes.");
            return new ArrayList<>();
        }

        final GraphqlResponse<IntrospectionQuery, Error> response = magentoGraphqlClient.executeIntrospection(
            FilterIntrospectionQuery.QUERY);

        // If there are errors in the response we'll log them out and return a safe but empty value
        if (response.getErrors() != null && response.getErrors().size() > 0) {
            response.getErrors().stream()
                .forEach(err -> LOGGER.error("An error has occurred: {} ({})", err.getMessage(), err.getCategory()));
            return new ArrayList<>();
        }

        return response.getData().getType().getInputFields();
    }

}
