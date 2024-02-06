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

package com.baidu.bifromq.mqtt.handler.v3;

import com.baidu.bifromq.inbox.storage.proto.LWT;
import com.baidu.bifromq.mqtt.handler.IMQTTProtocolHelper;
import com.baidu.bifromq.mqtt.handler.MQTTConnectHandler;
import com.baidu.bifromq.mqtt.handler.MQTTPersistentSessionHandler;
import com.baidu.bifromq.mqtt.handler.TenantSettings;
import com.baidu.bifromq.type.ClientInfo;
import io.netty.channel.ChannelHandlerContext;
import javax.annotation.Nullable;
import lombok.Builder;

public final class MQTT3PersistentSessionHandler extends MQTTPersistentSessionHandler {
    private final IMQTTProtocolHelper helper;

    @Builder
    public MQTT3PersistentSessionHandler(TenantSettings settings,
                                         String userSessionId,
                                         int keepAliveTimeSeconds,
                                         int sessionExpirySeconds,
                                         ClientInfo clientInfo,
                                         @Nullable MQTTConnectHandler.ExistingSession existingSession,
                                         @Nullable LWT willMessage,
                                         ChannelHandlerContext ctx) {
        super(settings,
            userSessionId,
            keepAliveTimeSeconds,
            sessionExpirySeconds,
            clientInfo,
            existingSession,
            willMessage, ctx);
        this.helper = new MQTT3ProtocolHelper(settings, clientInfo);
    }

    @Override
    protected IMQTTProtocolHelper helper() {
        return helper;
    }
}
