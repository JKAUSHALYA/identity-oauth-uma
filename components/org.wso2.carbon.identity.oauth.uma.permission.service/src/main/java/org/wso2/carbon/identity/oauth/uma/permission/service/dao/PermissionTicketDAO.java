/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.oauth.uma.permission.service.dao;

import org.wso2.carbon.database.utils.jdbc.NamedJdbcTemplate;
import org.wso2.carbon.database.utils.jdbc.exceptions.DataAccessException;
import org.wso2.carbon.database.utils.jdbc.exceptions.TransactionException;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.identity.oauth.uma.common.JdbcUtils;
import org.wso2.carbon.identity.oauth.uma.common.UMAConstants;
import org.wso2.carbon.identity.oauth.uma.common.exception.UMAClientException;
import org.wso2.carbon.identity.oauth.uma.common.exception.UMAServerException;
import org.wso2.carbon.identity.oauth.uma.permission.service.model.PermissionTicketModel;
import org.wso2.carbon.identity.oauth.uma.permission.service.model.Resource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Data Access Layer functionality for Permission Endpoint. This includes persisting requested permissions
 * (requested resource ids with their scopes) and the issued permission ticket.
 */
public class PermissionTicketDAO {

    private static final String UTC = "UTC";

    private static final String STORE_PT_QUERY = "INSERT INTO IDN_UMA_PERMISSION_TICKET " +
            "(PT, TIME_CREATED, EXPIRY_TIME, TICKET_STATE, TENANT_ID) VALUES " +
            "(:" + UMAConstants.SQLPlaceholders.PERMISSION_TICKET + ";,:" + UMAConstants.SQLPlaceholders.TIME_CREATED +
            ";,:" + UMAConstants.SQLPlaceholders.EXPIRY_TIME + ";,:" + UMAConstants.SQLPlaceholders.STATE + ";,:" +
            UMAConstants.SQLPlaceholders.TENANT_ID + ";)";
    private static final String STORE_PT_RESOURCE_IDS_QUERY = "INSERT INTO IDN_UMA_PT_RESOURCE " +
            "(PT_RESOURCE_ID, PT_ID) VALUES ((SELECT ID FROM IDN_UMA_RESOURCE WHERE RESOURCE_ID = :" +
            UMAConstants.SQLPlaceholders.RESOURCE_ID + ";),:" + UMAConstants.SQLPlaceholders.ID + ";)";
    private static final String STORE_PT_RESOURCE_SCOPES_QUERY = "INSERT INTO IDN_UMA_PT_RESOURCE_SCOPE " +
            "(PT_RESOURCE_ID, PT_SCOPE_ID) VALUES (:" + UMAConstants.SQLPlaceholders.ID + ";, " +
            "(SELECT ID FROM IDN_UMA_RESOURCE_SCOPE WHERE SCOPE_NAME = :" + UMAConstants.SQLPlaceholders.RESOURCE_SCOPE
            + "; AND RESOURCE_IDENTITY = (SELECT ID FROM IDN_UMA_RESOURCE WHERE RESOURCE_ID = :" +
            UMAConstants.SQLPlaceholders.RESOURCE_ID + ";)))";
    private static final String VALIDATE_REQUESTED_RESOURCE_IDS_WITH_REGISTERED_RESOURCE_IDS = "SELECT ID " +
            "FROM IDN_UMA_RESOURCE WHERE RESOURCE_ID = :" + UMAConstants.SQLPlaceholders.RESOURCE_ID + "; AND " +
            "RESOURCE_OWNER_NAME = :" + UMAConstants.SQLPlaceholders.RESOURCE_OWNER_NAME + "; AND USER_DOMAIN = :" +
            UMAConstants.SQLPlaceholders.USER_DOMAIN + "; AND CLIENT_ID = :" +
            UMAConstants.SQLPlaceholders.CLIENT_ID + ";";
    private static final String VALIDATE_REQUESTED_RESOURCE_SCOPES_WITH_REGISTERED_RESOURCE_SCOPES = "SELECT ID FROM" +
            " IDN_UMA_RESOURCE_SCOPE WHERE SCOPE_NAME = :" + UMAConstants.SQLPlaceholders.RESOURCE_SCOPE + "; AND " +
            "RESOURCE_IDENTITY = (SELECT ID FROM IDN_UMA_RESOURCE WHERE RESOURCE_ID = :" +
            UMAConstants.SQLPlaceholders.RESOURCE_ID + ";)";
    private static final String GET_EXPIRY_TIME_FOR_ACTIVE_PERMISSION_TICKET = "SELECT EXPIRY_TIME FROM " +
            "IDN_UMA_PERMISSION_TICKET WHERE PT = :" + UMAConstants.SQLPlaceholders.PERMISSION_TICKET + "; AND " +
            "TICKET_STATE = :" + UMAConstants.SQLPlaceholders.STATE + ";";
    private static final String UPDATE_PERMISSION_TICKET_STATE = "UPDATE IDN_UMA_PERMISSION_TICKET SET TICKET_STATE = :"
            + UMAConstants.SQLPlaceholders.STATE + "; WHERE PT = :" + UMAConstants.SQLPlaceholders.PERMISSION_TICKET +
            ";";
    private static final String VALIDATE_PERMISSION_TICKET = "SELECT PT FROM IDN_UMA_PERMISSION_TICKET WHERE PT = ? ;";

    private static final String RETRIEVE_RESOURCE_ID_STORE_IN_PT = "SELECT RESOURCE_ID FROM IDN_UMA_RESOURCE " +
            "INNER JOIN IDN_UMA_PT_RESOURCE ON IDN_UMA_RESOURCE.ID = IDN_UMA_PT_RESOURCE.PT_RESOURCE_ID INNER JOIN " +
            "IDN_UMA_PERMISSION_TICKET ON IDN_UMA_PT_RESOURCE.PT_ID = IDN_UMA_PERMISSION_TICKET.ID WHERE " +
            "IDN_UMA_PERMISSION_TICKET.PT = ?";

    private static final String RETRIEVE_RESOURCE_SCOPES_STORE_IN_PT = "SELECT RESOURCE.RESOURCE_ID, " +
            "IDN_SCOPES.SCOPE_NAME FROM IDN_UMA_RESOURCE_SCOPE AS IDN_SCOPES INNER JOIN " +
            "IDN_UMA_PT_RESOURCE_SCOPE PT_SCOPES ON IDN_SCOPES.ID = PT_SCOPES.PT_SCOPE_ID INNER JOIN " +
            "IDN_UMA_PT_RESOURCE PT_RESOURCE ON PT_SCOPES.PT_RESOURCE_ID = PT_RESOURCE.ID INNER JOIN " +
            "IDN_UMA_PERMISSION_TICKET ON PT_RESOURCE.PT_ID = IDN_UMA_PERMISSION_TICKET.ID INNER JOIN " +
            "IDN_UMA_RESOURCE RESOURCE ON RESOURCE.ID = IDN_SCOPES.RESOURCE_IDENTITY WHERE " +
            "IDN_UMA_PERMISSION_TICKET.PT = ?";

    private static final String SAVE_ACCESS_TOKEN_AGAINST_PERMISSION_TICKET =
            "UPDATE IDN_UMA_PERMISSION_TICKET SET ACCESS_TOKEN = ? WHERE PT = ?";

    private static final String RETRIEVE_PERMISSION_TICKET_FOR_ACCESS_TOKEN =
            "SELECT PT FROM IDN_UMA_PERMISSION_TICKET WHERE ACCESS_TOKEN = ?";

    /**
     * Issue a permission ticket. Permission ticket represents the resources requested by the resource server on
     * client's behalf
     *
     * @param resourceList          A list with the resource ids and the corresponding scopes.
     * @param permissionTicketModel Model class for permission ticket values.
     * @param resourceOwnerName     Resource owner name.
     * @param clientId              Client id representing the resource server.
     * @param userDomain            User domain of the resource owner.
     * @throws UMAServerException Exception thrown when there is a database issue.
     * @throws UMAClientException Exception thrown when there is an invalid resource ID/scope.
     */
    public static void persistPermissionTicket(List<Resource> resourceList, PermissionTicketModel permissionTicketModel,
                                               String resourceOwnerName, String clientId, String userDomain)
            throws UMAServerException, UMAClientException {

        checkResourceIdsExistence(resourceList, resourceOwnerName, clientId, userDomain);
        checkResourceScopesExistence(resourceList);

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        try {
            namedJdbcTemplate.withTransaction(namedTemplate -> {
                int insertedId = namedTemplate.
                        executeInsert(STORE_PT_QUERY,
                                (namedPreparedStatement -> {
                                    namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.PERMISSION_TICKET,
                                            permissionTicketModel.getTicket());
                                    namedPreparedStatement.setTimeStamp(UMAConstants.SQLPlaceholders.TIME_CREATED,
                                            permissionTicketModel.getCreatedTime(),
                                            Calendar.getInstance(TimeZone.getTimeZone(UTC)));
                                    namedPreparedStatement.setTimeStamp(UMAConstants.SQLPlaceholders.EXPIRY_TIME,
                                            permissionTicketModel.getExpiryTime(),
                                            Calendar.getInstance(TimeZone.getTimeZone(UTC)));
                                    namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.STATE,
                                            permissionTicketModel.getStatus());
                                    namedPreparedStatement.setLong(UMAConstants.SQLPlaceholders.TENANT_ID,
                                            permissionTicketModel.getTenantId());
                                }), permissionTicketModel, true);
                addRequestedResources(resourceList, insertedId);
                return null;
            });

        } catch (TransactionException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_PT, e);
        }
    }

    /**
     * Checks if the permission ticket sent by the client when requesting RPT has expired.
     *
     * @param permissionTicket permission ticket sent by the client.
     * @throws UMAServerException Exception thrown when there is a database error.
     * @throws UMAClientException Exception thrown when the permission ticket sent by the client has expired.
     */
    private static void checkPermissionTicketExpiration(String permissionTicket) throws UMAServerException,
            UMAClientException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        Timestamp expiryTime;

        try {
            expiryTime = namedJdbcTemplate.fetchSingleRecord(GET_EXPIRY_TIME_FOR_ACTIVE_PERMISSION_TICKET,
                    (resultSet, rowNumber) -> resultSet.getTimestamp(1),
                    namedPreparedStatement -> {
                        namedPreparedStatement.setString(
                                UMAConstants.SQLPlaceholders.PERMISSION_TICKET, permissionTicket);
                        namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.STATE,
                                UMAConstants.PermissionTicketStates.PERMISSION_TICKET_STATE_ACTIVE);
                    });

            if (expiryTime == null) {
                throw new UMAClientException(UMAConstants.ErrorMessages.ERROR_BAD_REQUEST_INVALID_PERMISSION_TICKET);
            }

            if (expiryTime.getTime() < System.currentTimeMillis()) {
                String permissionTicketState = UMAConstants.PermissionTicketStates.PERMISSION_TICKET_STATE_EXPIRED;
                updatePermissionTicketState(permissionTicket, permissionTicketState);
                throw new UMAClientException(UMAConstants.ErrorMessages.ERROR_BAD_REQUEST_INVALID_PERMISSION_TICKET);
            }
        } catch (DataAccessException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_CHECK_PERMISSION_TICKET_STATE, e);
        }

    }

    private static void updatePermissionTicketState(String permissionTicket, String permissionTicketState) throws
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();

        try {
            namedJdbcTemplate.executeUpdate(UPDATE_PERMISSION_TICKET_STATE, namedPreparedStatement -> {
                namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.PERMISSION_TICKET,
                        permissionTicket);
                namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.STATE, permissionTicketState);
            });
        } catch (DataAccessException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_UPDATE_PERMISSION_TICKET_STATE, e);
        }

    }

    private static void checkResourceIdsExistence(List<Resource> resourceList, String
            resourceOwnerName, String clientId, String userDomain) throws UMAClientException,
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        String resourceId;
        for (Resource resource : resourceList) {
            try {
                resourceId = namedJdbcTemplate.fetchSingleRecord(
                        VALIDATE_REQUESTED_RESOURCE_IDS_WITH_REGISTERED_RESOURCE_IDS, (resultSet, rowNumber) ->
                                resultSet.getString(1), namedPreparedStatement -> {
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                    resource.getResourceId());
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_OWNER_NAME,
                                    resourceOwnerName);
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.USER_DOMAIN, userDomain);
                            namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.CLIENT_ID,
                                    clientId);
                        }
                );
                if (resourceId == null) {
                    throw new UMAClientException(UMAConstants.ErrorMessages
                            .ERROR_BAD_REQUEST_INVALID_RESOURCE_ID, "Permission request failed with bad resource ID : "
                            + resource.getResourceId());
                }
            } catch (DataAccessException e) {
                throw new UMAServerException(UMAConstants.ErrorMessages
                        .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_CHECK_RESOURCE_ID_EXISTENCE, e);
            }
        }
    }

    private static void checkResourceScopesExistence(List<Resource> resourceList) throws UMAClientException,
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        String resourceScope;
        for (Resource resource : resourceList) {
            for (String scope : resource.getResourceScopes()) {
                try {
                    resourceScope = namedJdbcTemplate.fetchSingleRecord(
                            VALIDATE_REQUESTED_RESOURCE_SCOPES_WITH_REGISTERED_RESOURCE_SCOPES, (resultSet,
                                                                                                 rowNumber) ->
                                    resultSet.getString(1), namedPreparedStatement -> {
                                namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                        resource.getResourceId());
                                namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_SCOPE, scope);
                            }
                    );
                    if (resourceScope == null) {
                        throw new UMAClientException(UMAConstants.ErrorMessages.ERROR_BAD_REQUEST_INVALID_RESOURCE_SCOPE
                                , "Permission request failed with bad resource scope " + scope + " for resource " +
                                resource.getResourceId());
                    }
                } catch (DataAccessException e) {
                    throw new UMAServerException(UMAConstants.ErrorMessages
                            .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_CHECK_RESOURCE_SCOPE_EXISTENCE, e);
                }
            }
        }
    }

    private static void addRequestedResources(List<Resource> resourceList, int insertedId) throws
            UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        for (Resource resource : resourceList) {
            try {
                namedJdbcTemplate.withTransaction(namedtemplate -> {
                    int insertedResourceId = namedtemplate.executeInsert(STORE_PT_RESOURCE_IDS_QUERY,
                            (namedpreparedStatement -> {
                                namedpreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                        resource.getResourceId());
                                namedpreparedStatement.setLong(UMAConstants.SQLPlaceholders.ID, insertedId);
                            }), resource, true);
                    addResourceScopes(resource, insertedResourceId);
                    return null;
                });
            } catch (TransactionException e) {
                throw new UMAServerException(UMAConstants.ErrorMessages
                        .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_PT, e);
            }
        }
    }

    private static void addResourceScopes(Resource resource, int insertedResourceId) throws UMAServerException {

        NamedJdbcTemplate namedJdbcTemplate = JdbcUtils.getNewNamedTemplate();
        try {
            namedJdbcTemplate.withTransaction(namedtemplate -> {
                namedtemplate.executeBatchInsert(STORE_PT_RESOURCE_SCOPES_QUERY, (namedPreparedStatement -> {
                    namedPreparedStatement.setLong(UMAConstants.SQLPlaceholders.ID,
                            insertedResourceId);
                    for (String scope : resource.getResourceScopes()) {
                        namedPreparedStatement.setString(UMAConstants.SQLPlaceholders.RESOURCE_ID,
                                resource.getResourceId());
                        namedPreparedStatement.setString(
                                UMAConstants.SQLPlaceholders.RESOURCE_SCOPE, scope);
                        namedPreparedStatement.addBatch();
                    }
                }), insertedResourceId);
                return null;
            });
        } catch (TransactionException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_PT, e);
        }
    }

    /**
     * Validating permission Ticket and obtain resource id's and resource scopes which client requested.
     *
     * @param permissionTicket A correlation handle representing requested permissions by the client.
     * @return resource list represented by the permission ticket.
     * @throws UMAClientException
     * @throws UMAServerException
     */
    public static List<Resource> validatePermissionTicket(String permissionTicket) throws UMAClientException,
            UMAServerException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            try (PreparedStatement preparedStatement = connection.prepareStatement(VALIDATE_PERMISSION_TICKET)) {
                preparedStatement.setString(1, permissionTicket);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new UMAClientException(UMAConstants.ErrorMessages
                                .ERROR_BAD_REQUEST_INVALID_PERMISSION_TICKET);
                    } else {
                        checkPermissionTicketExpiration(permissionTicket);
                        List<Resource> list = retrieveResourceIdsInPT(permissionTicket);
                        retrieveResourceScopesInPT(permissionTicket, list);
                        return list;

                    }
                }
            }
        } catch (SQLException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_REQUESTED_PERMISSIONS, e);
        }
    }

    public static List<Resource> getResourcesForPermissionTicket(String permissionTicket)
            throws UMAClientException, UMAServerException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            try (PreparedStatement preparedStatement = connection.prepareStatement(VALIDATE_PERMISSION_TICKET)) {
                preparedStatement.setString(1, permissionTicket);
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new UMAClientException("No permission ticket found in DB.");
                    } else {
                        List<Resource> list = retrieveResourceIdsInPT(permissionTicket);
                        retrieveResourceScopesInPT(permissionTicket, list);
                        return list;

                    }
                }
            }
        } catch (SQLException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_REQUESTED_PERMISSIONS, e);
        }
    }


    public static String retrievePermissionTicketForAccessToken(String accessToken) throws UMAServerException {

        String permissionTicket = null;
        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            PreparedStatement preparedStatement = connection
                    .prepareStatement(RETRIEVE_PERMISSION_TICKET_FOR_ACCESS_TOKEN);
            preparedStatement.setString(1, accessToken);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    permissionTicket = resultSet.getString("PT");
                }
            }
        } catch (SQLException e) {
            throw new UMAServerException("Error occurred while retrieving permission ticket.", e);
        }

        return permissionTicket;
    }

    private static List<Resource> retrieveResourceIdsInPT(String permissionTicket) throws UMAServerException {

        Resource resource;
        List<Resource> listOfResourceIds = new ArrayList<Resource>();
        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement(RETRIEVE_RESOURCE_ID_STORE_IN_PT);
            preparedStatement.setString(1, permissionTicket);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                resource = new Resource();
                if (resultSet.getString(1) != null) {
                    resource.setResourceId(resultSet.getString(1));
                    listOfResourceIds.add(resource);
                }
            }
        } catch (SQLException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_REQUESTED_PERMISSIONS, e);
        }

        return listOfResourceIds;
    }

    private static void retrieveResourceScopesInPT(String permissionTicket, List<Resource> resources)
            throws UMAServerException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            PreparedStatement preparedStatement = connection.prepareStatement(RETRIEVE_RESOURCE_SCOPES_STORE_IN_PT);
            preparedStatement.setString(1, permissionTicket);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()) {
                for (Resource resourceList : resources) {
                    if (resultSet.getString(1).equals(resourceList.getResourceId())) {
                        if (!resourceList.getResourceScopes().contains(resultSet.getString(2))) {
                            resourceList.getResourceScopes().add(resultSet.getString(2));
                        }
                    }
                }
            }

        } catch (SQLException e) {
            throw new UMAServerException(UMAConstants.ErrorMessages
                    .ERROR_INTERNAL_SERVER_ERROR_FAILED_TO_PERSIST_REQUESTED_PERMISSIONS, e);
        }
    }

    public static void saveAccessTokenAgainstPermissionTicket(String accessToken, String permissionTicket)
            throws UMAServerException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            PreparedStatement preparedStatement = connection
                    .prepareStatement(SAVE_ACCESS_TOKEN_AGAINST_PERMISSION_TICKET);
            preparedStatement.setString(1, accessToken);
            preparedStatement.setString(2, permissionTicket);
            preparedStatement.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            throw new UMAServerException("Error occurred while updating the access token for the permission ticket.");
        }
    }
}
