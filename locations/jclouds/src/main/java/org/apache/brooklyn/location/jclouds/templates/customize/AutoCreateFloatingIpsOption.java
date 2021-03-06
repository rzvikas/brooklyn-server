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

package org.apache.brooklyn.location.jclouds.templates.customize;

import org.apache.brooklyn.location.jclouds.JcloudsLocationConfig;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.openstack.nova.v2_0.compute.options.NovaTemplateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AutoCreateFloatingIpsOption implements TemplateOptionCustomizer {

    private static final Logger LOG = LoggerFactory.getLogger(AutoCreateFloatingIpsOption.class);
    
    @Override
    public void apply(TemplateOptions t, ConfigBag props, Object v) {
        LOG.warn("Using deprecated " + JcloudsLocationConfig.AUTO_CREATE_FLOATING_IPS + "; use " + JcloudsLocationConfig.AUTO_ASSIGN_FLOATING_IP + " instead");
        if (t instanceof NovaTemplateOptions) {
            ((NovaTemplateOptions) t).autoAssignFloatingIp((Boolean) v);
        } else {
            LOG.info("ignoring auto-generate-floating-ips({}) in VM creation because not supported for cloud/type ({})", v, t);
        }
    }
}
