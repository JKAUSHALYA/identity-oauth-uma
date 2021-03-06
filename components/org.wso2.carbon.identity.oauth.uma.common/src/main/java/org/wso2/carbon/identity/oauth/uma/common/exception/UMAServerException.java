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

package org.wso2.carbon.identity.oauth.uma.common.exception;

import org.wso2.carbon.identity.oauth.uma.common.UMAConstants;

/**
 * Custom exception to be thrown when there is a server issue.
 */
public class UMAServerException extends UMAException {

    public UMAServerException(String message) {

        super(message);
    }

    public UMAServerException(String message, Throwable throwable) {

        super(message, throwable);
    }

    public UMAServerException(UMAConstants.ErrorMessages errorMessage, Throwable throwable) {

        super(errorMessage.getMessage(), throwable);
        this.setCode(errorMessage.getCode());
    }

    public UMAServerException(UMAConstants.ErrorMessages errorMessage) {

        super(errorMessage.getMessage());
        this.setCode(errorMessage.getCode());
    }

    public UMAServerException(UMAConstants.ErrorMessages errorMessage, String errorLogMessage, Throwable throwable) {

        super(errorLogMessage, throwable);
        this.setCode(errorMessage.getCode());
    }

}
