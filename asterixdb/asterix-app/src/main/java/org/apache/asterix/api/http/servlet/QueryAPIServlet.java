/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.api.http.servlet;

import javax.servlet.http.HttpServletRequest;

import org.apache.asterix.compiler.provider.ILangCompilationProvider;
import org.apache.asterix.lang.common.base.Statement;
import org.apache.asterix.translator.IStatementExecutorFactory;

public class QueryAPIServlet extends RESTAPIServlet {
    private static final long serialVersionUID = 1L;
    private static final byte ALLOWED_CATEGORIES = Statement.Category.QUERY;

    public QueryAPIServlet(ILangCompilationProvider compilationProvider,
            IStatementExecutorFactory queryTranslatorFactory) {
        super(compilationProvider, queryTranslatorFactory);
    }

    @Override
    protected String getQueryParameter(HttpServletRequest request) {
        return request.getParameter("query");
    }

    @Override
    protected byte getAllowedCategories() {
        return ALLOWED_CATEGORIES;
    }

    @Override
    protected String getErrorMessage() {
        return "Invalid statement: Non-query statement %s to the query API.";
    }
}
