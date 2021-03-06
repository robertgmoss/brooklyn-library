/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.test.entity;

import org.apache.brooklyn.entity.java.VanillaJavaAppImpl;
import org.apache.brooklyn.entity.webapp.WebAppServiceConstants;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

public class TestJavaWebAppEntityImpl extends VanillaJavaAppImpl implements TestJavaWebAppEntity {

    @SetFromFlag public int a;
    @SetFromFlag public int b;
    @SetFromFlag public int c;

    @Override
    public synchronized void spoofRequest() {
        Integer rc = getAttribute(WebAppServiceConstants.REQUEST_COUNT);
        if (rc==null) rc = 0;
        sensors().set(WebAppServiceConstants.REQUEST_COUNT, rc+1);
    }

    @Override
    public int getA() {
        return a;
    }
    
    @Override
    public int getB() {
        return b;
    }
    
    @Override
    public int getC() {
        return c;
    }

}
