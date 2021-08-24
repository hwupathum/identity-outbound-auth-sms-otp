/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.com).
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.smsotp.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.smsotp.common.constant.Constants;
import org.wso2.carbon.identity.smsotp.common.dto.ErrorDTO;
import org.wso2.carbon.identity.smsotp.common.dto.GenerationResponseDTO;
import org.wso2.carbon.identity.smsotp.common.dto.SessionDTO;
import org.wso2.carbon.identity.smsotp.common.dto.ValidationResponseDTO;
import org.wso2.carbon.identity.smsotp.common.exception.SMSOTPException;
import org.wso2.carbon.identity.smsotp.common.exception.SMSOTPServerException;
import org.wso2.carbon.identity.smsotp.common.internal.SMSOTPServiceDataHolder;
import org.wso2.carbon.identity.smsotp.common.util.OneTimePasswordUtils;
import org.wso2.carbon.identity.smsotp.common.util.Utils;
import org.wso2.carbon.identity.application.authentication.framework.store.SessionDataStore;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.governance.service.notification.NotificationChannels;
import org.wso2.carbon.identity.recovery.IdentityRecoveryConstants;
import org.wso2.carbon.identity.recovery.internal.IdentityRecoveryServiceDataHolder;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.UserStoreManager;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.carbon.user.core.common.User;
import org.wso2.carbon.user.core.constants.UserCoreErrorConstants;

import java.io.IOException;
import java.util.HashMap;

import java.util.Properties;
import java.util.UUID;

/**
 * This class implements the {@link SMSOTPService} interface.
 */
public class SMSOTPServiceImpl implements SMSOTPService {

    private static final Log log = LogFactory.getLog(SMSOTPService.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public GenerationResponseDTO generateSMSOTP(String userId) throws SMSOTPException {

        if (StringUtils.isBlank(userId)) {
            throw Utils.handleClientException(Constants.ErrorMessage.CLIENT_EMPTY_USER_ID, null);
        }
        // Retrieve user by ID.
        AbstractUserStoreManager userStoreManager;
        User user;
        try {
            userStoreManager = (AbstractUserStoreManager) SMSOTPServiceDataHolder.getInstance()
                    .getRealmService().getTenantUserRealm(getTenantId()).getUserStoreManager();
            user = userStoreManager.getUser(userId, null);
        } catch (UserStoreException e) {
            // Handle user not found.
            String errorCode = ((org.wso2.carbon.user.core.UserStoreException) e).getErrorCode();
            if (UserCoreErrorConstants.ErrorMessages.ERROR_CODE_NON_EXISTING_USER.getCode().equals(errorCode)) {
                throw Utils.handleClientException(Constants.ErrorMessage.CLIENT_INVALID_USER_ID, userId);
            }
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_USER_STORE_MANAGER_ERROR,
                    String.format("Error while retrieving user for the Id : %s.", userId), e);
        }
        // Check if the user exist.
        if (user == null) {
            throw Utils.handleClientException(Constants.ErrorMessage.CLIENT_INVALID_USER_ID, userId);
        }

        // Retrieve mobile number if notifications are managed internally.
        boolean sendNotification = Boolean.parseBoolean(
                Utils.readConfigurations().getProperty(Constants.SMS_OTP_TRIGGER_NOTIFICATION));
        String mobileNumber = null;
        if (sendNotification) {
            mobileNumber = getMobileNumber(user.getUsername(), userStoreManager);
        }
        if (sendNotification && StringUtils.isBlank(mobileNumber)) {
            throw Utils.handleClientException(Constants.ErrorMessage.CLIENT_BLANK_MOBILE_NUMBER,
                    user.getFullQualifiedUsername());
        }

        SessionDTO sessionDTO = issueOTP(mobileNumber, user);

        GenerationResponseDTO otpDto = new GenerationResponseDTO();
        otpDto.setSmsOTP(sessionDTO.getOtp());
        return otpDto;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResponseDTO validateSMSOTP(String transactionId, String userId, String smsOTP)
            throws SMSOTPException {

        // Sanitize inputs.
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(smsOTP)) {
            String missingParam = StringUtils.isBlank(userId) ? "userId" : "smsOTP";
            throw Utils.handleClientException(
                    Constants.ErrorMessage.CLIENT_MANDATORY_VALIDATION_PARAMETERS_EMPTY, missingParam);
        }

        // Should the reason be exposed upon a failed OTP validation.
        boolean showFailureReason = Boolean.parseBoolean(
                StringUtils.trim(Utils.readConfigurations().getProperty(Constants.SMS_OTP_SHOW_FAILURE_REASON)));

        // Retrieve session from the database.
        String sessionId = String.valueOf(userId.hashCode());
        String jsonString = (String) SessionDataStore.getInstance()
                .getSessionData(sessionId, Constants.SESSION_TYPE_OTP);
        if (StringUtils.isBlank(jsonString)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("No OTP session found for the user : %s.", userId));
            }
            ErrorDTO error = showFailureReason ? new ErrorDTO(Constants.ErrorMessage.CLIENT_NO_OTP_FOR_USER, userId)
                    : null;
            return new ValidationResponseDTO(userId, false, error);
        }
        SessionDTO sessionDTO;
        try {
            sessionDTO = new ObjectMapper().readValue(jsonString, SessionDTO.class);
        } catch (IOException e) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_JSON_SESSION_MAPPER_ERROR, null, e);
        }

        ValidationResponseDTO validationResponseDTO = isValid(sessionDTO, smsOTP, userId, showFailureReason);
        if (!validationResponseDTO.isValid()) {
            return validationResponseDTO;
        }
        // Valid OTP. Clear OTP session data.
        SessionDataStore.getInstance().clearSessionData(sessionId, Constants.SESSION_TYPE_OTP);
        return new ValidationResponseDTO(userId, true);
    }

    private ValidationResponseDTO isValid(SessionDTO sessionDTO, String smsOTP, String userId,
                                          boolean showFailureReason) {

        ErrorDTO error;
        // Check if the provided OTP is correct.
        if (!StringUtils.equals(smsOTP, sessionDTO.getOtp())) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Invalid OTP provided for the user : %s.", userId));
            }
            error = showFailureReason ? new ErrorDTO(Constants.ErrorMessage.CLIENT_OTP_VALIDATION_FAILED, userId)
                    : null;
            return new ValidationResponseDTO(userId, false, error);
        }
        // Check for expired OTPs.
        if (System.currentTimeMillis() - sessionDTO.getGeneratedTime() >= sessionDTO.getExpiryTime()) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Expired OTP provided for the user : %s.", userId));
            }
            error = showFailureReason ? new ErrorDTO(Constants.ErrorMessage.CLIENT_EXPIRED_OTP, userId) : null;
            return new ValidationResponseDTO(userId, false, error);
        }
        return new ValidationResponseDTO(userId, true);
    }

    private SessionDTO issueOTP(String mobileNumber, User user) throws SMSOTPException {

        // Read server configurations.
        Properties properties = Utils.readConfigurations();
        String otpValidityPeriodValue =
                StringUtils.trim(properties.getProperty(Constants.SMS_OTP_TOKEN_VALIDITY_PERIOD));
        String otpRenewIntervalValue = StringUtils.trim(properties.getProperty(Constants.SMS_OTP_TOKEN_RENEW_INTERVAL));
        // Notification sending, defaults to false.
        boolean triggerNotification =
                StringUtils.isNotBlank(properties.getProperty(Constants.SMS_OTP_TRIGGER_NOTIFICATION)) &&
                        Boolean.parseBoolean(properties.getProperty(Constants.SMS_OTP_TRIGGER_NOTIFICATION));
        // If not defined, use the default values.
        int otpValidityPeriod = StringUtils.isNumeric(otpValidityPeriodValue) ?
                Integer.parseInt(otpValidityPeriodValue) : Constants.DEFAULT_SMS_OTP_VALIDITY_PERIOD;
        // If not defined, defaults to zero to renew always.
        int otpRenewalInterval = StringUtils.isNumeric(otpRenewIntervalValue) ?
                Integer.parseInt(otpRenewIntervalValue) : 0;
        // Should we send the same OTP when asked to resend.
        boolean resendSameOtpEnabled = otpRenewalInterval > 0 && otpRenewalInterval < otpValidityPeriod;

        // If 'resending same OTP' is enabled, check if such exists.
        SessionDTO sessionDTO = null;
        if (resendSameOtpEnabled) {
            sessionDTO = getPreviousValidSession(user, otpRenewalInterval);
        }

        // If no such valid OTPs exist, generate a new OTP and proceed.
        if (sessionDTO == null) {
            sessionDTO = generateNewOTP(user, otpValidityPeriod);
        }

        // Sending SMS notifications.
        if (triggerNotification) {
            triggerNotification(user, mobileNumber, sessionDTO.getOtp());
        }
        return sessionDTO;
    }

    private SessionDTO generateNewOTP(User user, int otpExpiryTime) throws SMSOTPServerException {

        // Read server configs.
        Properties properties = Utils.readConfigurations();
        boolean isAlphaNumericOtpEnabled = Boolean.parseBoolean(
                properties.getProperty(Constants.SMS_OTP_ALPHANUMERIC_TOKEN_ENABLED));
        String otpLengthValue = StringUtils.trim(properties.getProperty(Constants.SMS_OTP_TOKEN_LENGTH));

        int otpLength = StringUtils.isNumeric(otpLengthValue) ?
                Integer.parseInt(otpLengthValue) : Constants.DEFAULT_OTP_LENGTH;

        // Generate OTP.
        String otp = OneTimePasswordUtils.generateOTP(
                UUID.randomUUID().toString(),
                String.valueOf(Constants.NUMBER_BASE),
                otpLength,
                isAlphaNumericOtpEnabled);
        // Save the otp in the 'IDN_AUTH_SESSION_STORE' table.
        SessionDTO sessionDTO = new SessionDTO();
        sessionDTO.setOtp(otp);
        sessionDTO.setGeneratedTime(System.currentTimeMillis());
        sessionDTO.setExpiryTime(otpExpiryTime);
        sessionDTO.setFullQualifiedUserName(user.getFullQualifiedUsername());
        sessionDTO.setUserId(user.getUserID());
        String jsonString;
        try {
            jsonString = new ObjectMapper().writeValueAsString(sessionDTO);
        } catch (JsonProcessingException e) {
            throw Utils.handleServerException(
                    Constants.ErrorMessage.SERVER_SESSION_JSON_MAPPER_ERROR, e.getMessage(), e);
        }
        String sessionId = String.valueOf(user.getUserID().hashCode());
        SessionDataStore.getInstance().storeSessionData(sessionId, Constants.SESSION_TYPE_OTP, jsonString,
                getTenantId());
        if (log.isDebugEnabled()) {
            log.debug(String.format("Successfully persisted the OTP for the user Id: %s.", sessionDTO.getUserId()));
        }
        return sessionDTO;
    }

    private void triggerNotification(User user, String mobileNumber, String otp) throws SMSOTPException {

        if (log.isDebugEnabled()) {
            log.debug(String.format("Sending SMS OTP notification to user Id: %s.", user.getUserID()));
        }

        HashMap<String, Object> properties = new HashMap<>();
        properties.put(IdentityEventConstants.EventProperty.USER_NAME, user.getUsername());
        properties.put(IdentityEventConstants.EventProperty.USER_STORE_DOMAIN, user.getUserStoreDomain());
        properties.put(IdentityEventConstants.EventProperty.TENANT_DOMAIN, user.getTenantDomain());
        properties.put(IdentityEventConstants.EventProperty.NOTIFICATION_CHANNEL,
                NotificationChannels.SMS_CHANNEL.getChannelType());
        properties.put(IdentityRecoveryConstants.TEMPLATE_TYPE, Constants.SMS_OTP_NOTIFICATION_TEMPLATE);
        properties.put(IdentityRecoveryConstants.SEND_TO, mobileNumber);
        properties.put(IdentityRecoveryConstants.CONFIRMATION_CODE, otp);

        Event event = new Event(IdentityEventConstants.Event.TRIGGER_SMS_NOTIFICATION, properties);
        try {
            IdentityRecoveryServiceDataHolder.getInstance().getIdentityEventService().handleEvent(event);
        } catch (IdentityEventException e) {
            throw Utils.handleServerException(
                    Constants.ErrorMessage.SERVER_NOTIFICATION_SENDING_ERROR, user.getFullQualifiedUsername(), e);
        }
    }

    private String getMobileNumber(String username, UserStoreManager userStoreManager)
            throws SMSOTPServerException {

        String mobileNumber;
        try {
            mobileNumber = userStoreManager.getUserClaimValue(
                    username,
                    NotificationChannels.SMS_CHANNEL.getClaimUri(),
                    null);
        } catch (UserStoreException e) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_RETRIEVING_MOBILE_ERROR, username, e);
        }
        if (StringUtils.isBlank(mobileNumber)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("No mobile number found for the user: %s.", username));
            }
            return null;
        }
        return mobileNumber;
    }

    private SessionDTO getPreviousValidSession(User user, int otpRenewalInterval) throws SMSOTPException {

        // Search previous session object.
        String sessionId = String.valueOf(user.getUserID().hashCode());
        String jsonString = (String) SessionDataStore.getInstance().
                getSessionData(sessionId, Constants.SESSION_TYPE_OTP);
        if (StringUtils.isBlank(jsonString)) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("No valid sessions found for the user Id: %s.", user.getUserID()));
            }
            return null;
        }
        SessionDTO previousSessionDTO;
        try {
            previousSessionDTO = new ObjectMapper().readValue(jsonString, SessionDTO.class);
        } catch (IOException e) {
            throw Utils.handleServerException(Constants.ErrorMessage.SERVER_JSON_SESSION_MAPPER_ERROR, null, e);
        }
        // If the previous OTP is issued within the interval, return the same.
        return (System.currentTimeMillis() - previousSessionDTO.getGeneratedTime() < otpRenewalInterval) ?
                previousSessionDTO : null;
    }

    private int getTenantId() {

        return PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId();
    }
}
