/*
 * (C) Copyright IBM Corp. 2019, 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.persistence.jdbc.dao.impl;

import static com.ibm.fhir.persistence.jdbc.JDBCConstants.UTC;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.ibm.fhir.config.FHIRRequestContext;
import com.ibm.fhir.persistence.exception.FHIRPersistenceException;
import com.ibm.fhir.persistence.jdbc.dao.api.IResourceReferenceDAO;
import com.ibm.fhir.persistence.jdbc.dao.api.JDBCIdentityCache;
import com.ibm.fhir.persistence.jdbc.dto.CompositeParmVal;
import com.ibm.fhir.persistence.jdbc.dto.DateParmVal;
import com.ibm.fhir.persistence.jdbc.dto.ExtractedParameterValue;
import com.ibm.fhir.persistence.jdbc.dto.ExtractedParameterValueVisitor;
import com.ibm.fhir.persistence.jdbc.dto.LocationParmVal;
import com.ibm.fhir.persistence.jdbc.dto.NumberParmVal;
import com.ibm.fhir.persistence.jdbc.dto.QuantityParmVal;
import com.ibm.fhir.persistence.jdbc.dto.ReferenceParmVal;
import com.ibm.fhir.persistence.jdbc.dto.StringParmVal;
import com.ibm.fhir.persistence.jdbc.dto.TokenParmVal;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDataAccessException;
import com.ibm.fhir.persistence.jdbc.impl.ParameterTransactionDataImpl;
import com.ibm.fhir.schema.control.FhirSchemaConstants;

/**
 * Batch insert into the parameter values tables. Avoids having to create one stored procedure
 * per resource type, because the row type array approach apparently won't work with dynamic
 * SQL (EXECUTE ... USING ...). Unfortunately this means we have more database round-trips, we
 * don't have a choice.
 */
public class ParameterVisitorBatchDAO implements ExtractedParameterValueVisitor, AutoCloseable {
    private static final Logger logger = Logger.getLogger(ParameterVisitorBatchDAO.class.getName());

    private static final String HISTORY = "_history";
    private static final String HTTP = "http:";
    private static final String HTTPS = "https:";
    private static final String URN = "urn:";

    // the connection to use for the inserts
    private final Connection connection;

    // the max number of rows we accumulate for a given statement before we submit the batch
    private final int batchSize;

    // FK to the logical resource for the parameters being added
    private final long logicalResourceId;

    // Maintainers: remember to close all statements in AutoCloseable#close()
    private final String insertString;
    private final PreparedStatement strings;
    private int stringCount;

    private final String insertNumber;
    private final PreparedStatement numbers;
    private int numberCount;

    private final String insertDate;
    private final PreparedStatement dates;
    private int dateCount;

    private final PreparedStatement tokens;
    // token is the most common component type in composite params, so prepare a statement for it
    private final PreparedStatement tokenComp;
    private int tokenCount;

    private final String insertQuantity;
    private final PreparedStatement quantities;
    private int quantityCount;

    // rarely used so no need for {@code java.sql.PreparedStatement} or batching on this one
    // even on Location resources its only there once by default
    private final String insertLocation;

    private final PreparedStatement composites;
    private int compositesCount;

    // Searchable string attributes stored at the Resource (system) level
    private final PreparedStatement resourceStrings;
    private int resourceStringCount;

    // Searchable date attributes stored at the Resource (system) level
    private final PreparedStatement resourceDates;
    private int resourceDateCount;

    // Searchable token attributes stored at the Resource (system) level
    private final PreparedStatement resourceTokens;
    private int resourceTokenCount;

    // DAO for handling parameters stored as token values
    private final IResourceReferenceDAO resourceReferenceDAO;

    // Collect a list of token values to process in one go
    private final List<ResourceTokenValueRec> tokenValueRecs = new ArrayList<>();

    // The table prefix (resourceType)
    private final String tablePrefix;

    // The common cache for all our identity lookup needs
    private final JDBCIdentityCache identityCache;

    // The server base "https://example.com:9443/" extracted the first time we need it
    private String serverBase;

    // If not null, we stash certain parameter data here for insertion later
    private final ParameterTransactionDataImpl transactionData;

    /**
     * Public constructor
     * @param c
     * @param resourceId
     */
    public ParameterVisitorBatchDAO(Connection c, String adminSchemaName, String tablePrefix, boolean multitenant, long logicalResourceId, int batchSize,
            JDBCIdentityCache identityCache, IResourceReferenceDAO resourceReferenceDAO, ParameterTransactionDataImpl ptdi) throws SQLException {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }

        this.connection = c;
        this.logicalResourceId = logicalResourceId;
        this.batchSize = batchSize;
        this.identityCache = identityCache;
        this.resourceReferenceDAO = resourceReferenceDAO;
        this.tablePrefix = tablePrefix;
        this.transactionData = ptdi;

        insertString = multitenant ?
                "INSERT INTO " + tablePrefix + "_str_values (mt_id, parameter_name_id, str_value, str_value_lcase, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                :
                "INSERT INTO " + tablePrefix + "_str_values (parameter_name_id, str_value, str_value_lcase, logical_resource_id) VALUES (?,?,?,?)";
        strings = c.prepareStatement(insertString);

        insertNumber = multitenant ?
                "INSERT INTO " + tablePrefix + "_number_values (mt_id, parameter_name_id, number_value, number_value_low, number_value_high, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?,?)"
                :
                "INSERT INTO " + tablePrefix + "_number_values (parameter_name_id, number_value, number_value_low, number_value_high, logical_resource_id) VALUES (?,?,?,?,?)";
        numbers = c.prepareStatement(insertNumber);

        insertDate = multitenant ?
                "INSERT INTO " + tablePrefix + "_date_values (mt_id, parameter_name_id, date_start, date_end, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                :
                "INSERT INTO " + tablePrefix + "_date_values (parameter_name_id, date_start, date_end, logical_resource_id) VALUES (?,?,?,?)";
        dates = c.prepareStatement(insertDate);

        String insertToken = multitenant ?
                "INSERT INTO " + tablePrefix + "_token_values (mt_id, parameter_name_id, code_system_id, token_value, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                :
                "INSERT INTO " + tablePrefix + "_token_values (parameter_name_id, code_system_id, token_value, logical_resource_id) VALUES (?,?,?,?)";
        tokens = c.prepareStatement(insertToken);
        tokenComp = c.prepareStatement(insertToken, Statement.RETURN_GENERATED_KEYS);

        insertQuantity = multitenant ?
                "INSERT INTO " + tablePrefix + "_quantity_values (mt_id, parameter_name_id, code_system_id, code, quantity_value, quantity_value_low, quantity_value_high, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?,?,?,?)"
                :
                "INSERT INTO " + tablePrefix + "_quantity_values (parameter_name_id, code_system_id, code, quantity_value, quantity_value_low, quantity_value_high, logical_resource_id) VALUES (?,?,?,?,?,?,?)";
        quantities = c.prepareStatement(insertQuantity);

        insertLocation = multitenant ? "INSERT INTO " + tablePrefix + "_latlng_values (mt_id, parameter_name_id, latitude_value, longitude_value, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                : "INSERT INTO " + tablePrefix + "_latlng_values (parameter_name_id, latitude_value, longitude_value, logical_resource_id) VALUES (?,?,?,?)";

        String insertComposite = multitenant ?
                "INSERT INTO " + tablePrefix + "_composites (mt_id, parameter_name_id, logical_resource_id, "
                + "comp1_str, comp1_number, comp1_date, comp1_token, comp1_quantity, comp1_latlng, "
                + "comp2_str, comp2_number, comp2_date, comp2_token, comp2_quantity, comp2_latlng, "
                + "comp3_str, comp3_number, comp3_date, comp3_token, comp3_quantity, comp3_latlng"
                + ") VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,  ?,?,?,?,?,?,  ?,?,?,?,?,?,  ?,?,?,?,?,?)"
                :
                "INSERT INTO " + tablePrefix + "_composites (parameter_name_id, logical_resource_id, "
                + "comp1_str, comp1_number, comp1_date, comp1_token, comp1_quantity, comp1_latlng, "
                + "comp2_str, comp2_number, comp2_date, comp2_token, comp2_quantity, comp2_latlng, "
                + "comp3_str, comp3_number, comp3_date, comp3_token, comp3_quantity, comp3_latlng"
                + ") VALUES (?,?,  ?,?,?,?,?,?,  ?,?,?,?,?,?,  ?,?,?,?,?,?)";
        composites = c.prepareStatement(insertComposite);

        // Resource level string attributes
        String insertResourceString = multitenant ?
                "INSERT INTO resource_str_values (mt_id, parameter_name_id, str_value, str_value_lcase, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                :
                "INSERT INTO resource_str_values (parameter_name_id, str_value, str_value_lcase, logical_resource_id) VALUES (?,?,?,?)";
        resourceStrings = c.prepareStatement(insertResourceString);

        // Resource level date attributes
        String insertResourceDate = multitenant ?
                "INSERT INTO resource_date_values (mt_id, parameter_name_id, date_start, date_end, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                :
                "INSERT INTO resource_date_values (parameter_name_id, date_start, date_end, logical_resource_id) VALUES (?,?,?,?)";
        resourceDates = c.prepareStatement(insertResourceDate);

        // Resource level token attributes
        String insertResourceToken = multitenant ?
                "INSERT INTO resource_token_values (mt_id, parameter_name_id, code_system_id, token_value, logical_resource_id) VALUES (" + adminSchemaName + ".sv_tenant_id,?,?,?,?)"
                :
                "INSERT INTO resource_token_values (parameter_name_id, code_system_id, token_value, logical_resource_id) VALUES (?,?,?,?)";
        resourceTokens = c.prepareStatement(insertResourceToken);
    }

    /**
     * Look up the normalized id for the parameter, adding it to the parameter_names table if it doesn't yet exist
     * @param parameterName
     * @return
     */
    protected int getParameterNameId(String parameterName) throws FHIRPersistenceException {
        return identityCache.getParameterNameId(parameterName);
    }

    /**
     * Looks up the code system. If it doesn't exist, adds it to the database
     * @param codeSystem
     * @return
     */
    protected int getCodeSystemId(String codeSystem) throws FHIRPersistenceException {
        return identityCache.getCodeSystemId(codeSystem);
    }

    @Override
    public void visit(StringParmVal param) throws FHIRPersistenceException {
        String parameterName = param.getName();
        String value = param.getValueString();

        while (value != null && value.getBytes().length > FhirSchemaConstants.MAX_SEARCH_STRING_BYTES) {
            // keep chopping the string in half until its byte representation fits inside
            // the VARCHAR
            value = value.substring(0, value.length() / 2);
        }

        try {
            int parameterNameId = getParameterNameId(parameterName);
            if (isBase(param)) {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("baseStringValue: " + parameterName + "[" + parameterNameId + "], " + value);
                }

                resourceStrings.setInt(1, parameterNameId);
                if (value != null) {
                    resourceStrings.setString(2, value);
                    resourceStrings.setString(3, value.toLowerCase());
                }
                else {
                    resourceStrings.setNull(2, Types.VARCHAR);
                    resourceStrings.setNull(3, Types.VARCHAR);
                }
                resourceStrings.setLong(4, logicalResourceId);
                resourceStrings.addBatch();

                if (++resourceStringCount == this.batchSize) {
                    resourceStrings.executeBatch();
                    resourceStringCount = 0;
                }
            }
            else {
                // standard resource property
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("stringValue: " + parameterName + "[" + parameterNameId + "], " + value);
                }

                setStringParms(strings, parameterNameId, value);
                strings.addBatch();

                if (++stringCount == this.batchSize) {
                    strings.executeBatch();
                    stringCount = 0;
                }
            }
        }
        catch (SQLException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + "=" + value, x);
        }
    }

    private void setStringParms(PreparedStatement insert, int parameterNameId, String value) throws SQLException {
        insert.setInt(1, parameterNameId);
        if (value != null) {
            insert.setString(2, value);
            insert.setString(3, value.toLowerCase());
        }
        else {
            insert.setNull(2, Types.VARCHAR);
            insert.setNull(3, Types.VARCHAR);
        }
        insert.setLong(4, logicalResourceId);
    }

    @Override
    public void visit(NumberParmVal param) throws FHIRPersistenceException {
        String parameterName = param.getName();
        BigDecimal value = param.getValueNumber();
        BigDecimal valueLow = param.getValueNumberLow();
        BigDecimal valueHigh = param.getValueNumberHigh();

        try {
            int parameterNameId = getParameterNameId(parameterName);

            if (logger.isLoggable(Level.FINE)) {
                logger.fine("numberValue: " + parameterName + "[" + parameterNameId + "], "
                        + value + " [" + valueLow + ", " + valueHigh + "]");
            }

            setNumberParms(numbers, parameterNameId, value, valueLow, valueHigh);
            numbers.addBatch();

            if (++numberCount == this.batchSize) {
                numbers.executeBatch();
                numberCount = 0;
            }
        }
        catch (SQLException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + "={" + value + " ["+ valueLow + "," + valueHigh + "}", x);
        }
    }

    private void setNumberParms(PreparedStatement insert, int parameterNameId, BigDecimal value, BigDecimal valueLow, BigDecimal valueHigh) throws SQLException {
        insert.setInt(1, parameterNameId);
        insert.setBigDecimal(2, value);
        insert.setBigDecimal(3, valueLow);
        insert.setBigDecimal(4, valueHigh);
        insert.setLong(5, logicalResourceId);
    }

    @Override
    public void visit(DateParmVal param) throws FHIRPersistenceException {
        String parameterName = param.getName();
        Timestamp dateStart = param.getValueDateStart();
        Timestamp dateEnd = param.getValueDateEnd();
        try {
            int parameterNameId = getParameterNameId(parameterName);

            if (isBase(param)) {
                // store in the base (resource) table
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("baseDateValue: " + parameterName + "[" + parameterNameId + "], "
                             + "[" + dateStart + ", " + dateEnd + "]");
                }

                // Insert record into the base level date attribute table
                setDateParms(resourceDates, parameterNameId, dateStart, dateEnd);
                resourceDates.addBatch();

                if (++resourceDateCount == this.batchSize) {
                    resourceDates.executeBatch();
                    resourceDateCount = 0;
                }
            }
            else {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("dateValue: " + parameterName + "[" + parameterNameId + "], "
                            + "period: [" + dateStart + ", " + dateEnd + "]");
                }

                setDateParms(dates, parameterNameId, dateStart, dateEnd);
                dates.addBatch();

                if (++dateCount == this.batchSize) {
                    dates.executeBatch();
                    dateCount = 0;
                }
            }
        }
        catch (SQLException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + "={" + dateStart + ", " + dateEnd + "}", x);
        }

    }

    private void setDateParms(PreparedStatement insert, int parameterNameId, Timestamp dateStart, Timestamp dateEnd) throws SQLException {
        insert.setInt(1, parameterNameId);
        insert.setTimestamp(2, dateStart, UTC);
        insert.setTimestamp(3, dateEnd, UTC);
        insert.setLong(4, logicalResourceId);
    }

    @Override
    public void visit(TokenParmVal param) throws FHIRPersistenceException {
        String parameterName = param.getName();
        String codeSystem = param.getValueSystem();
        String tokenValue = param.getValueCode();
        try {
            int parameterNameId = getParameterNameId(parameterName);

            // handle base (non-resource-specific) token values for issue #1366
            if (isBase(param)) {
                int codeSystemId = getCodeSystemId(codeSystem);

                // store in the base (resource) table
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("baseTokenValue: " + parameterName + "[" + parameterNameId + "], "
                            + codeSystem + "[" + codeSystemId + "], " + tokenValue);
                }

                resourceTokens.setInt(1, parameterNameId);
                resourceTokens.setInt(2, codeSystemId);
                resourceTokens.setString(3, tokenValue);
                resourceTokens.setLong(4, logicalResourceId);
                resourceTokens.addBatch();

                if (++resourceTokenCount == this.batchSize) {
                    resourceTokens.executeBatch();
                    resourceTokenCount = 0;
                }
            }
            else {
                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("tokenValue: " + parameterName + "[" + parameterNameId + "], "
                            + codeSystem + ", " + tokenValue);
                }

                // Add the new token value to the collection we're building...what's the resourceTypeId?
                final int resourceTypeId = identityCache.getResourceTypeId(param.getResourceType());
                if (tokenValue == null) {
                    logger.info("tokenValue is NULL for: " + parameterName + "[" + parameterNameId + "], " + codeSystem);
                }

                ResourceTokenValueRec rec = new ResourceTokenValueRec(parameterNameId, param.getResourceType(), resourceTypeId, logicalResourceId, codeSystem, tokenValue);
                if (this.transactionData != null) {
                    this.transactionData.addValue(rec);
                } else {
                    this.tokenValueRecs.add(rec);
                }
            }
        }
        catch (FHIRPersistenceDataAccessException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + "=" + codeSystem + ":" + tokenValue, x);
        }
        catch (SQLException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + "=" + codeSystem + ":" + tokenValue, x);
        }
    }

    private void setTokenParms(PreparedStatement insert, int parameterNameId, int codeSystemId, String tokenValue) throws SQLException {
        insert.setInt(1, parameterNameId);
        insert.setInt(2, codeSystemId);
        insert.setString(3, tokenValue);
        insert.setLong(4, logicalResourceId);
    }

    @Override
    public void visit(QuantityParmVal param) throws FHIRPersistenceException {
        String parameterName = param.getName();
        String code = param.getValueCode();
        String codeSystem = param.getValueSystem();
        BigDecimal quantityValue = param.getValueNumber();
        BigDecimal quantityLow = param.getValueNumberLow();
        BigDecimal quantityHigh = param.getValueNumberHigh();

        // XXX why no check for isBase on this one?

        // Skip anything with a null code
        if (code == null || code.isEmpty()) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("CODELESS QUANTITY (skipped): " + parameterName + "=" + code + ":" + codeSystem + "{" + quantityValue + ", " + quantityLow + ", " + quantityHigh + "}");
            }
        }
        else {
            try {
                int parameterNameId = getParameterNameId(parameterName);

                if (logger.isLoggable(Level.FINE)) {
                    logger.fine("quantityValue: " + parameterName + "[" + parameterNameId + "], "
                            + quantityValue + " [" + quantityLow + ", " + quantityHigh + "]");
                }

                setQuantityParms(quantities, parameterNameId, codeSystem, code, quantityValue, quantityLow, quantityHigh);
                quantities.addBatch();

                if (++quantityCount == batchSize) {
                    quantities.executeBatch();
                    quantityCount = 0;
                }
            }
            catch (FHIRPersistenceDataAccessException x) {
                // wrap the exception so we have more context about the parameter causing the problem
                throw new FHIRPersistenceDataAccessException(parameterName + "=" + code + ":" + codeSystem + "{" + quantityValue + ", " + quantityLow + ", " + quantityHigh + "}", x);
            }
            catch (SQLException x) {
                throw new FHIRPersistenceDataAccessException(parameterName + "=" + code + ":" + codeSystem + "{" + quantityValue + ", " + quantityLow + ", " + quantityHigh + "}", x);
            }
        }

    }

    private void setQuantityParms(PreparedStatement insert, int parameterNameId, String codeSystem, String code, BigDecimal quantityValue, BigDecimal quantityLow, BigDecimal quantityHigh)
            throws SQLException, FHIRPersistenceException {
        insert.setInt(1, parameterNameId);
        insert.setInt(2, getCodeSystemId(codeSystem));
        insert.setString(3, code);
        insert.setBigDecimal(4, quantityValue);
        insert.setBigDecimal(5, quantityLow);
        insert.setBigDecimal(6, quantityHigh);
        insert.setLong(7, logicalResourceId);
    }

    @Override
    public void visit(LocationParmVal param) throws FHIRPersistenceException {
        String parameterName = param.getName();
        double lat = param.getValueLatitude();
        double lng = param.getValueLongitude();

        try {
            PreparedStatement insert = connection.prepareStatement(insertLocation);
            setLocationParms(insert, getParameterNameId(parameterName), lat, lng);
            insert.executeUpdate();
        }
        catch (SQLException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + "={" + lat + ", " + lng + "}", x);
        }
    }

    private void setLocationParms(PreparedStatement insert, int parameterNameId, double lat, double lng) throws SQLException, FHIRPersistenceException {
        insert.setInt(1, parameterNameId);
        insert.setDouble(2, lat);
        insert.setDouble(3, lng);
        insert.setLong(4, logicalResourceId);
    }

    @Override
    public void visit(CompositeParmVal compositeParameter) throws FHIRPersistenceException {
        String parameterName = compositeParameter.getName();

        List<ExtractedParameterValue> component = compositeParameter.getComponent();
        if (component.size() > 3) {
            throw new UnsupportedOperationException("Expected 3 or fewer components, but found " + component.size());
        }

        try {
            int i = 1;
            int parameterNameId = getParameterNameId(parameterName);
            composites.setInt(i++, parameterNameId);
            composites.setLong(i++, logicalResourceId);

            for (ExtractedParameterValue val : component) {
                // TODO figure out how to use the visitor here and still get back the generated id
                // THE ORDER OF THESE IF STATEMENTS MUST MATCH THE ORDER OF THE INSERT FIELDS
                if (val instanceof StringParmVal) {
                    try (PreparedStatement insert = connection.prepareStatement(insertString, Statement.RETURN_GENERATED_KEYS)) {
                        setStringParms(insert, parameterNameId, ((StringParmVal) val).getValueString());
                        insert.executeUpdate();
                        // closing the insert statement also closes the resultset
                        ResultSet rs = insert.getGeneratedKeys();
                        if (rs.next()) {
                            composites.setLong(i++, rs.getLong(1));
                        }
                    }
                } else {
                    composites.setNull(i++, Types.BIGINT);
                }

                if (val instanceof NumberParmVal) {
                    try (PreparedStatement insert = connection.prepareStatement(insertNumber, Statement.RETURN_GENERATED_KEYS)) {
                        NumberParmVal number = (NumberParmVal) val;
                        setNumberParms(insert, parameterNameId, number.getValueNumber(), number.getValueNumberLow(), number.getValueNumberHigh());
                        insert.executeUpdate();
                        // closing the insert statement also closes the resultset
                        ResultSet rs = insert.getGeneratedKeys();
                        if (rs.next()) {
                            composites.setLong(i++, rs.getLong(1));
                        }
                    }
                } else {
                    composites.setNull(i++, Types.BIGINT);
                }

                if (val instanceof DateParmVal) {
                    try (PreparedStatement insert = connection.prepareStatement(insertDate, Statement.RETURN_GENERATED_KEYS)) {
                        DateParmVal dVal = (DateParmVal) val;
                        setDateParms(insert, parameterNameId, dVal.getValueDateStart(), dVal.getValueDateEnd());
                        insert.executeUpdate();
                        // closing the insert statement also closes the resultset
                        ResultSet rs = insert.getGeneratedKeys();
                        if (rs.next()) {
                            composites.setLong(i++, rs.getLong(1));
                        }
                    }
                } else {
                    composites.setNull(i++, Types.BIGINT);
                }

                if (val instanceof TokenParmVal) {
                    TokenParmVal tVal = (TokenParmVal) val;
                    setTokenParms(tokenComp, parameterNameId, getCodeSystemId(tVal.getValueSystem()), tVal.getValueCode());
                    tokenComp.executeUpdate();
                    try (ResultSet rs = tokenComp.getGeneratedKeys()) {
                        if (rs.next()) {
                            composites.setLong(i++, rs.getLong(1));
                        }
                    }
                } else {
                    composites.setNull(i++, Types.BIGINT);
                }

                if (val instanceof QuantityParmVal) {
                    try (PreparedStatement insert = connection.prepareStatement(insertQuantity, Statement.RETURN_GENERATED_KEYS)) {
                        QuantityParmVal qVal = (QuantityParmVal) val;
                        setQuantityParms(insert, parameterNameId, qVal.getValueSystem(), qVal.getValueCode(),
                                qVal.getValueNumber(), qVal.getValueNumberLow(), qVal.getValueNumberHigh());
                        insert.executeUpdate();
                        // closing the insert statement also closes the resultset
                        ResultSet rs = insert.getGeneratedKeys();
                        if (rs.next()) {
                            composites.setLong(i++, rs.getLong(1));
                        }
                    }
                } else {
                    composites.setNull(i++, Types.BIGINT);
                }

                if (val instanceof LocationParmVal) {
                    try (PreparedStatement insert = connection.prepareStatement(insertLocation, Statement.RETURN_GENERATED_KEYS)) {
                        LocationParmVal lVal = (LocationParmVal) val;
                        setLocationParms(insert, parameterNameId, lVal.getValueLatitude(), lVal.getValueLongitude());
                        insert.executeUpdate();
                        // closing the insert statement also closes the resultset
                        ResultSet rs = insert.getGeneratedKeys();
                        if (rs.next()) {
                            composites.setLong(i++, rs.getLong(1));
                        }
                    }
                } else {
                    composites.setNull(i++, Types.BIGINT);
                }
            }
            while (i <= 20) {
                composites.setNull(i++, Types.BIGINT);
            }
            composites.addBatch();

            if (++compositesCount == this.batchSize) {
                composites.executeBatch();
                compositesCount = 0;
            }
        } catch (SQLException x) {
            throw new FHIRPersistenceDataAccessException(parameterName + " of composite " +
                    component.stream().map(c -> c.getClass().getSimpleName()).collect(Collectors.joining(",")), x);
        }
    }

    @Override
    public void close() throws Exception {
        // flush any stragglers, remembering to reset each count because
        // close() should be idempotent.
        try {
            if (stringCount > 0) {
                strings.executeBatch();
                stringCount = 0;
            }

            if (numberCount > 0) {
                numbers.executeBatch();
                numberCount = 0;
            }

            if (dateCount > 0) {
                dates.executeBatch();
                dateCount = 0;
            }

            if (tokenCount > 0) {
                tokens.executeBatch();
                tokenCount = 0;
            }

            if (quantityCount > 0) {
                quantities.executeBatch();
                quantityCount = 0;
            }

            if (compositesCount > 0) {
                composites.executeBatch();
                compositesCount = 0;
            }

            if (resourceStringCount > 0) {
                resourceStrings.executeBatch();
                resourceStringCount = 0;
            }

            if (resourceDateCount > 0) {
                resourceDates.executeBatch();
                resourceDateCount = 0;
            }

            if (resourceTokenCount > 0) {
                resourceTokens.executeBatch();
                resourceTokenCount = 0;
            }

        }
        catch (SQLException x) {
            SQLException batchException = x.getNextException();
            if (batchException != null) {
                // We're really interested in the underlying cause here
                throw batchException;
            }
            else {
                throw x;
            }
        }

        // Process any tokens and references we've collected along the way
        if (!tokenValueRecs.isEmpty()) {
            this.resourceReferenceDAO.addCommonTokenValues(this.tablePrefix, tokenValueRecs);
        }

        closeStatement(strings);
        closeStatement(numbers);
        closeStatement(dates);
        closeStatement(tokens);
        closeStatement(tokenComp);
        closeStatement(quantities);
        closeStatement(resourceStrings);
        closeStatement(resourceDates);
        closeStatement(resourceTokens);
    }

    /**
     * Quietly close the given statement
     * @param ps
     */
    private void closeStatement(PreparedStatement ps) {
        try {
            ps.close();
        }
        catch (SQLException x) {
            logger.warning("failed to close statement");
        }
    }

    private boolean isBase(ExtractedParameterValue param) {
        return "Resource".equals(param.getBase());
    }

    /**
     * Get the leading part of the url e.g. https://example.com
     * @return
     */
    private String getServerUrl() throws FHIRPersistenceException {

        if (this.serverBase != null) {
            return this.serverBase;
        }

        String uri = FHIRRequestContext.get().getOriginalRequestUri();

        // request URI is not set for all unit-tests, so we need to take that into account
        if (uri == null) {
            return null;
        }

        try {
            StringBuilder result = new StringBuilder();
            URL url = new URL(uri);

            result.append(url.getProtocol());
            result.append("://");
            result.append(url.getHost());

            if (url.getPort() != -1) {
                result.append(":");
                result.append(url.getPort());
            }

            // https://example.com:9443/
            result.append("/");

            final String endpoint = "fhir-server/api/v4/";
            if (uri.contains(endpoint)) {
                result.append(endpoint);
            }

            // Cache the result so we don't have to compute it over and over
            this.serverBase = result.toString();
            return this.serverBase;
        } catch (MalformedURLException x) {
            // not very likely at this point
            logger.severe("Malformed server URL: " + uri);
            throw new FHIRPersistenceException("Server URL is malformed!");
        }
    }

    @Override
    public void visit(ReferenceParmVal rpv) throws FHIRPersistenceException {
        String valueString = rpv.getValueString();
        if (valueString == null || valueString.isEmpty()) {
            return;
        }

        final String resourceType = this.tablePrefix;
        int parameterNameId = getParameterNameId(rpv.getName());
        Integer resourceTypeId = identityCache.getResourceTypeId(resourceType);
        if (resourceTypeId == null) {
            // resourceType is not sensitive, so it's OK to include in the exception message
            throw new FHIRPersistenceException("Resource type not found in cache: '" + resourceType + "'");
        }

        final String base = getServerUrl();
        ResourceTokenValueRec rec;
        if (base != null && valueString.startsWith(base)) {
            // - relative reference https://example.com/Patient/123
            // Because this reference is to a local FHIR resource (inside this server), we need use the correct
            // resource type name (assigned as the code system)
            //  - https://localhost:9443/fhir-server/api/v4/Patient/1234
            //  - https://example.com/Patient/1234
            //  - https://example.com/Patient/1234/_history/2
            valueString = valueString.substring(base.length());

            // Patient/1234
            // Patient/1234/_history/2
            String[] tokens = valueString.split("/");
            if (tokens.length > 1) {
                String refResourceType = tokens[0];
                String refLogicalId = tokens[1];
                Integer refVersion = null;
                if (tokens.length == 4 && HISTORY.equals(tokens[2])) {
                    // versioned reference
                    refVersion = Integer.parseInt(tokens[3]);
                }

                // Store a token value configured as a reference to another resource
                rec = new ResourceTokenValueRec(parameterNameId, resourceType, resourceTypeId, logicalResourceId, refResourceType, refLogicalId, refVersion);
            } else {
                // stored as a token with the default system
                rec = new ResourceTokenValueRec(parameterNameId, resourceType, resourceTypeId, logicalResourceId, TokenParmVal.DEFAULT_TOKEN_SYSTEM, valueString);
            }
        } else if (valueString.startsWith(HTTP) || valueString.startsWith(HTTPS) || valueString.startsWith(URN)) {
            //  - absolute URL ==> http://some.system/a/fhir/resource/path
            //  - absolute URI ==> urn:uuid:53fefa32-1111-2222-3333-55ee120877b7
            // stored as a token with the default system
            rec = new ResourceTokenValueRec(parameterNameId, resourceType, resourceTypeId, logicalResourceId, TokenParmVal.DEFAULT_TOKEN_SYSTEM, valueString);
        } else if (valueString.startsWith("#")) {
            //  - Internal ==> #fragmentid1
            // stored as a token value with the default system
            rec = new ResourceTokenValueRec(parameterNameId, resourceType, resourceTypeId, logicalResourceId, TokenParmVal.DEFAULT_TOKEN_SYSTEM, valueString);
        } else {
            //  - Relative ==> Patient/1234
            //  - Relative ==> Patient/1234/_history/2
            String[] tokens = valueString.split("/");
            if (tokens.length > 1) {
                String refResourceType = tokens[0];
                String refLogicalId = tokens[1];
                Integer refVersion = null;
                if (tokens.length == 4 && HISTORY.equals(tokens[2])) {
                    // versioned reference
                    refVersion = Integer.parseInt(tokens[3]);
                }

                // Store a token value configured as a reference to another resource
                rec = new ResourceTokenValueRec(parameterNameId, resourceType, resourceTypeId, logicalResourceId, refResourceType, refLogicalId, refVersion);

            } else {
                // SearchReferenceTest system integration tests require support for arbitrary reference strings
                //  - Relative ==> 1234
                final String codeSystem = TokenParmVal.DEFAULT_TOKEN_SYSTEM;
                rec = new ResourceTokenValueRec(parameterNameId, resourceType, resourceTypeId, logicalResourceId, codeSystem, valueString);
            }
        }

        if (this.transactionData != null) {
            this.transactionData.addValue(rec);
        } else {
            this.tokenValueRecs.add(rec);
        }
    }
}