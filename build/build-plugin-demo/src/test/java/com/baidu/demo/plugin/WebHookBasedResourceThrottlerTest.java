/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package com.baidu.demo.plugin;/*
 * Copyright (c) 2024. The BifroMQ Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

import static com.bifromq.plugin.resourcethrottler.TenantResourceType.TotalConnections;
import static org.awaitility.Awaitility.await;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class WebHookBasedResourceThrottlerTest {
    private TestThrottlerServer testServer;

    @BeforeMethod
    private void setup() {
        testServer = new TestThrottlerServer();
        testServer.start();
    }

    @AfterMethod
    private void tearDown() {
        testServer.stop();
    }

    @Test
    public void testQuery() {
        WebHookBasedResourceThrottler throttler = new WebHookBasedResourceThrottler(testServer.getURI());
        assertTrue(throttler.hasResource("tenantA", TotalConnections));
        testServer.throttle("tenantA", TotalConnections);
        await().until(() -> !throttler.hasResource("tenantA", TotalConnections));
        testServer.release("tenantA", TotalConnections);
        await().until(() -> throttler.hasResource("tenantA", TotalConnections));
    }
}
