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

package com.baidu.bifromq.mqtt.utils;

import static io.netty.buffer.ByteBufUtil.utf8Bytes;

import io.netty.handler.codec.mqtt.MqttConnAckMessage;
import io.netty.handler.codec.mqtt.MqttConnAckVariableHeader;
import io.netty.handler.codec.mqtt.MqttConnectMessage;
import io.netty.handler.codec.mqtt.MqttConnectPayload;
import io.netty.handler.codec.mqtt.MqttConnectVariableHeader;
import io.netty.handler.codec.mqtt.MqttMessage;
import io.netty.handler.codec.mqtt.MqttMessageIdAndPropertiesVariableHeader;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPubReplyMessageVariableHeader;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttPublishVariableHeader;
import io.netty.handler.codec.mqtt.MqttReasonCodeAndPropertiesVariableHeader;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import io.netty.handler.codec.mqtt.MqttUnsubAckMessage;
import io.netty.handler.codec.mqtt.MqttUnsubAckPayload;
import io.netty.handler.codec.mqtt.MqttUnsubscribeMessage;
import io.netty.handler.codec.mqtt.MqttUnsubscribePayload;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MQTTMessageSizer {
    public static final int MIN_CONTROL_PACKET_SIZE = 10;

    public record MqttMessageSize(MqttVarHeaderBytes varHeaderBytes, int payloadBytes) {
        private static final MqttMessageSize ZERO_BYTES_REMAINING_LENGTH = new MqttMessageSize(null, 0);
        private static final MqttMessageSize TWO_BYTES_REMAINING_LENGTH =
            new MqttMessageSize(new MqttVarHeaderBytes(2, 0, 0), 0);

        static MqttMessageSize zeroBytesRemainingLength() {
            return ZERO_BYTES_REMAINING_LENGTH;
        }

        static MqttMessageSize twoBytesRemainingLength() {
            return TWO_BYTES_REMAINING_LENGTH;
        }

        public int encodedBytes() {
            return encodedBytes(true, true);
        }

        public int encodedBytes(boolean includeUserProps, boolean includeReasonString) {
            if (varHeaderBytes == null) {
                // 1 byte for fixHeader byte0
                // 1 byte for encoding 0 remainingLength
                return 2;
            }
            int totalVarHeaderBytes = varHeaderBytes.minBytes;
            if (includeUserProps) {
                totalVarHeaderBytes += varHeaderBytes.userPropsBytes;
            }
            if (includeReasonString) {
                totalVarHeaderBytes += varHeaderBytes.reasonStringBytes;
            }
            totalVarHeaderBytes = varIntBytes(totalVarHeaderBytes) + totalVarHeaderBytes;
            // 1 byte for fixHeader byte0
            // varInt encoding remainingLength(variableHeaderBytes + payloadBytes)
            return 1 + varIntBytes(totalVarHeaderBytes + payloadBytes) + totalVarHeaderBytes + payloadBytes;
        }
    }

    public record MqttVarHeaderBytes(int minBytes, int reasonStringBytes, int userPropsBytes) {

    }

    public record MqttPropertiesBytes(int minBytes, int reasonStringBytes, int userPropsBytes) {
    }

    public static MqttMessageSize size(MqttMessage message) {
        switch (message.fixedHeader().messageType()) {
            case CONNECT -> {
                MqttConnectMessage connMsg = ((MqttConnectMessage) message);
                return new MqttMessageSize(
                    sizeConnVarHeader(connMsg.variableHeader()),
                    sizeConnPayload(connMsg.payload())
                );
            }
            case CONNACK -> {
                MqttConnAckMessage connMsg = ((MqttConnAckMessage) message);
                return new MqttMessageSize(sizeConnAckVarHeader(connMsg.variableHeader()), 0);
            }
            case PUBLISH -> {
                MqttPublishMessage pubMsg = (MqttPublishMessage) message;
                return new MqttMessageSize(sizePubVarHeader(pubMsg.variableHeader()), pubMsg.payload().readableBytes());
            }
            case PUBACK -> {
                if (message.variableHeader() instanceof MqttPubReplyMessageVariableHeader pubReplyVarHeader) {
                    if (pubReplyVarHeader.reasonCode() != MqttPubReplyMessageVariableHeader.REASON_CODE_OK &&
                        !pubReplyVarHeader.properties().isEmpty()) {
                        MqttVarHeaderBytes varHeaderBytes = sizePubReplyHeader(pubReplyVarHeader);
                        return new MqttMessageSize(varHeaderBytes, 0);
                    }
                }
                //  The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Success) and there are no Properties. In this case the PUBACK has a Remaining Length of 2. [MQTT5-3.4.2.1]
                return MqttMessageSize.twoBytesRemainingLength();

            }
            case PUBREC -> {
                if (message.variableHeader() instanceof MqttPubReplyMessageVariableHeader pubReplyVarHeader) {
                    if (pubReplyVarHeader.reasonCode() != MqttPubReplyMessageVariableHeader.REASON_CODE_OK &&
                        !pubReplyVarHeader.properties().isEmpty()) {
                        MqttVarHeaderBytes varHeaderBytes = sizePubReplyHeader(pubReplyVarHeader);
                        return new MqttMessageSize(varHeaderBytes, 0);
                    }
                }
                // The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Success) and there are no Properties. In this case the PUBREC has a Remaining Length of 2 [MQTT5-3.5.2.1]
                return MqttMessageSize.twoBytesRemainingLength();
            }
            case PUBREL -> {
                if (message.variableHeader() instanceof MqttPubReplyMessageVariableHeader pubReplyVarHeader) {
                    if (pubReplyVarHeader.reasonCode() != MqttPubReplyMessageVariableHeader.REASON_CODE_OK &&
                        !pubReplyVarHeader.properties().isEmpty()) {
                        MqttVarHeaderBytes varHeaderBytes = sizePubReplyHeader(pubReplyVarHeader);
                        return new MqttMessageSize(varHeaderBytes, 0);
                    }
                }
                // The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Success) and there are no Properties. In this case the PUBREL has a Remaining Length of 2 [MQTT5-3.6.2.1]
                return MqttMessageSize.twoBytesRemainingLength();
            }
            case PUBCOMP -> {
                if (message.variableHeader() instanceof MqttPubReplyMessageVariableHeader pubReplyVarHeader) {
                    if (pubReplyVarHeader.reasonCode() != MqttPubReplyMessageVariableHeader.REASON_CODE_OK &&
                        !pubReplyVarHeader.properties().isEmpty()) {
                        MqttVarHeaderBytes varHeaderBytes = sizePubReplyHeader(pubReplyVarHeader);
                        return new MqttMessageSize(varHeaderBytes, 0);
                    }
                }
                // The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Success) and there are no Properties. In this case the PUBCOMP has a Remaining Length of 2 [MQTT5-3.7.2.1]
                return MqttMessageSize.twoBytesRemainingLength();
            }
            case SUBSCRIBE -> {
                MqttSubscribeMessage subMsg = (MqttSubscribeMessage) message;
                return new MqttMessageSize(
                    sizeIdAndPropsVarHeader(subMsg.idAndPropertiesVariableHeader()),
                    sizeSubPayload(subMsg.payload())
                );
            }
            case SUBACK -> {
                MqttSubAckMessage subAckMsg = (MqttSubAckMessage) message;
                return new MqttMessageSize(
                    sizeIdAndPropsVarHeader(subAckMsg.idAndPropertiesVariableHeader()),
                    sizeSubAckPayload(subAckMsg.payload())
                );
            }
            case UNSUBSCRIBE -> {
                MqttUnsubscribeMessage unsubMsg = (MqttUnsubscribeMessage) message;
                return new MqttMessageSize(
                    sizeIdAndPropsVarHeader(unsubMsg.idAndPropertiesVariableHeader()),
                    sizeUnsubPayload(unsubMsg.payload())
                );
            }
            case UNSUBACK -> {
                MqttUnsubAckMessage unsubAckMsg = (MqttUnsubAckMessage) message;
                return new MqttMessageSize(
                    sizeIdAndPropsVarHeader(unsubAckMsg.idAndPropertiesVariableHeader()),
                    sizeUnsubAckPayload(unsubAckMsg.payload())
                );
            }
            case DISCONNECT -> {
                if (message.variableHeader() == null) {
                    // The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Normal disconnecton) and there are no Properties. In this case the DISCONNECT has a Remaining Length of 0 [MQTT5-3.14.2.1]
                    return MqttMessageSize.zeroBytesRemainingLength();
                } else {
                    MqttVarHeaderBytes varHeaderBytes = sizeReasonCodeAndPropertiesVarHeader(
                        (MqttReasonCodeAndPropertiesVariableHeader) message.variableHeader());
                    return new MqttMessageSize(varHeaderBytes, 0);
                }
            }
            case PINGREQ, PINGRESP -> {
                return MqttMessageSize.zeroBytesRemainingLength();
            }
            case AUTH -> {
                if (message.variableHeader() == null) {
                    // The Reason Code and Property Length can be omitted if the Reason Code is 0x00 (Success) and there are no Properties. In this case the AUTH has a Remaining Length of 0 [MQTT5-3.15.2.1]
                    return MqttMessageSize.zeroBytesRemainingLength();
                } else {
                    MqttVarHeaderBytes varHeaderBytes = sizeReasonCodeAndPropertiesVarHeader(
                        (MqttReasonCodeAndPropertiesVariableHeader) message.variableHeader());
                    return new MqttMessageSize(varHeaderBytes, 0);
                }
            }
            default -> {
                log.error("Unknown message type for sizing: {}", message.fixedHeader().messageType());
                return MqttMessageSize.ZERO_BYTES_REMAINING_LENGTH;
            }
        }
    }

    private static MqttVarHeaderBytes sizeConnVarHeader(MqttConnectVariableHeader header) {
        MqttPropertiesBytes propsBytes = sizeMqttProperties(header.properties());
        // 6 bytes for UTF8 encoded string(MQTT)
        // 1 byte for ProtocolVersion
        // 1 byte for ConnectFlags
        // 2 bytes for keepAlive value
        return new MqttVarHeaderBytes(10 + propsBytes.minBytes,
            propsBytes.reasonStringBytes,
            propsBytes.userPropsBytes);
    }

    private static MqttVarHeaderBytes sizeConnAckVarHeader(MqttConnAckVariableHeader header) {
        MqttPropertiesBytes propsBytes = sizeMqttProperties(header.properties());
        // 1 byte for ConnectAcknowledgeFlags
        // 1 byte for ReasonCode
        return new MqttVarHeaderBytes(2 + propsBytes.minBytes, propsBytes.reasonStringBytes, propsBytes.userPropsBytes);
    }

    private static MqttVarHeaderBytes sizeReasonCodeAndPropertiesVarHeader(
        MqttReasonCodeAndPropertiesVariableHeader header) {
        MqttPropertiesBytes mqttPropsBytes = sizeMqttProperties(header.properties());
        // 1 byte for encoding reason code
        return new MqttVarHeaderBytes(1 + mqttPropsBytes.minBytes, mqttPropsBytes.reasonStringBytes,
            mqttPropsBytes.userPropsBytes);
    }

    private static int sizeConnPayload(MqttConnectPayload payload) {
        int clientIdBytes = utf8Bytes(payload.clientIdentifier());
        int usernameBytes = payload.userName() != null ? utf8Bytes(payload.userName()) : 0;
        int passwordBytes = payload.passwordInBytes().length;
        int payloadSize = clientIdBytes + usernameBytes + passwordBytes;
        if (payload.willTopic() != null) {
            payloadSize += utf8Bytes(payload.willTopic());
            payloadSize += payload.willMessageInBytes().length;
            MqttPropertiesBytes willPropBytes = sizeMqttProperties(payload.willProperties());
            payloadSize += willPropBytes.minBytes + willPropBytes.reasonStringBytes + willPropBytes.userPropsBytes;
        }
        return payloadSize;
    }

    private static MqttVarHeaderBytes sizePubVarHeader(MqttPublishVariableHeader header) {
        int topicNameBytes = utf8Bytes(header.topicName());
        // A PUBLISH packet MUST NOT contain a Packet Identifier if its QoS value is set to 0 [MQTT5-2.2.1-2]
        int packetIdBytes = header.packetId() == 0 ? 0 : 2;
        MqttPropertiesBytes propBytes = sizeMqttProperties(header.properties());
        return new MqttVarHeaderBytes(
            topicNameBytes + packetIdBytes + propBytes.minBytes,
            propBytes.reasonStringBytes,
            propBytes.userPropsBytes);
    }

    private static MqttVarHeaderBytes sizePubReplyHeader(MqttPubReplyMessageVariableHeader header) {
        MqttPropertiesBytes propBytes = sizeMqttProperties(header.properties());
        // 2 bytes for encoding packetId
        // 1 byte for encoding reason code
        return new MqttVarHeaderBytes(3 + propBytes.minBytes, propBytes.reasonStringBytes, propBytes.userPropsBytes);
    }

    private static MqttVarHeaderBytes sizeIdAndPropsVarHeader(MqttMessageIdAndPropertiesVariableHeader header) {
        MqttPropertiesBytes propsBytes = sizeMqttProperties(header.properties());
        // 2 bytes for encoding packetId
        return new MqttVarHeaderBytes(2 + propsBytes.minBytes, propsBytes.reasonStringBytes, propsBytes.userPropsBytes);
    }

    private static int sizeSubPayload(MqttSubscribePayload payload) {
        int totalBytes = 0;
        for (MqttTopicSubscription sub : payload.topicSubscriptions()) {
            // 1 byte for encoding subscription options
            totalBytes += 1 + sizeUTF8EncodedString(sub.topicName());
        }
        return totalBytes;
    }

    private static int sizeUnsubPayload(MqttUnsubscribePayload payload) {
        int totalBytes = 0;
        for (String topicFilter : payload.topics()) {
            totalBytes += sizeUTF8EncodedString(topicFilter);
        }
        return totalBytes;
    }

    private static int sizeSubAckPayload(MqttSubAckPayload payload) {
        // 1 byte for each reason code
        return payload.reasonCodes().size();
    }

    private static int sizeUnsubAckPayload(MqttUnsubAckPayload payload) {
        // 1 byte for each reason code
        return payload.unsubscribeReasonCodes().size();
    }

    private static MqttPropertiesBytes sizeMqttProperties(MqttProperties mqttProps) {
        int minBytes = 0;
        int reasonStringBytes = 0;
        int userPropsBytes = 0;
        for (MqttProperties.MqttProperty<?> mqttProperty : mqttProps.listAll()) {
            switch (MqttProperties.MqttPropertyType.valueOf(mqttProperty.propertyId())) {
                case PAYLOAD_FORMAT_INDICATOR -> minBytes += sizePacketFormatIndicator(mqttProperty);
                case PUBLICATION_EXPIRY_INTERVAL -> minBytes += sizeMessageExpiryInterval(mqttProperty);
                case CONTENT_TYPE -> minBytes += sizeContentType(mqttProperty);
                case RESPONSE_TOPIC -> minBytes += sizeResponseTopic(mqttProperty);
                case CORRELATION_DATA -> minBytes += sizeCorrelationData(mqttProperty);
                case SUBSCRIPTION_IDENTIFIER -> minBytes += sizeSubscriptionIdentifier(mqttProperty);
                case SESSION_EXPIRY_INTERVAL -> userPropsBytes += sizeSessionExpiryInterval(mqttProperty);
                case ASSIGNED_CLIENT_IDENTIFIER -> minBytes += sizeAssignedIdentifier(mqttProperty);
                case SERVER_KEEP_ALIVE -> minBytes += sizeServerKeepAlive(mqttProperty);
                case AUTHENTICATION_METHOD -> minBytes += sizeAuthMethod(mqttProperty);
                case AUTHENTICATION_DATA -> minBytes += sizeAuthData(mqttProperty);
                case REQUEST_PROBLEM_INFORMATION -> minBytes += sizeRequestProblemInformation(mqttProperty);
                case WILL_DELAY_INTERVAL -> minBytes += sizeWillDelayInterval(mqttProperty);
                case REQUEST_RESPONSE_INFORMATION -> minBytes += sizeRequestResponseInformation(mqttProperty);
                case RESPONSE_INFORMATION -> minBytes += sizeResponseInformaiton(mqttProperty);
                case SERVER_REFERENCE -> minBytes += sizeServerReference(mqttProperty);
                case REASON_STRING -> reasonStringBytes += sizeReasonStringProp(mqttProperty);
                case RECEIVE_MAXIMUM -> minBytes += sizeReceiveMaximum(mqttProperty);
                case TOPIC_ALIAS_MAXIMUM -> minBytes += sizeTopicAliasMaximum(mqttProperty);
                case TOPIC_ALIAS -> minBytes += sizeTopicAlias(mqttProperty);
                case MAXIMUM_QOS -> minBytes += sizeMaximumQoS(mqttProperty);
                case RETAIN_AVAILABLE -> minBytes += sizeRetainAvailable(mqttProperty);
                case USER_PROPERTY -> userPropsBytes += sizeUserProp(mqttProperty);
                case MAXIMUM_PACKET_SIZE -> minBytes += sizeMaximumPacketSize(mqttProperty);
                case WILDCARD_SUBSCRIPTION_AVAILABLE -> minBytes += sizeWildcardSubscriptionAvailable(mqttProperty);
                case SUBSCRIPTION_IDENTIFIER_AVAILABLE -> minBytes += sizeSubscriptionIdentifierAvailable(mqttProperty);
                case SHARED_SUBSCRIPTION_AVAILABLE -> minBytes += sizeSharedSubscriptionAvailable(mqttProperty);
            }
            return new MqttPropertiesBytes(minBytes, reasonStringBytes, userPropsBytes);
        }
        // The Property Length is encoded as a Variable Byte Integer. The Property Length does not include the bytes used to encode itself, but includes the length of the Properties. If there are no properties, this MUST be indicated by including a Property Length of zero [MQTT5-2.2.2-1].
        return new MqttPropertiesBytes(minBytes, reasonStringBytes, userPropsBytes);
    }

    private static <T> int sizePacketFormatIndicator(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x01) PacketFormatIndicator
        // 1 byte for encoding value 0|1
        return 2;
    }

    private static <T> int sizeMessageExpiryInterval(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x02) MessageExpiryInterval
        // 4 byte5 for encoding expiry interval value
        return 5;
    }

    private static <T> int sizeContentType(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x03) ContentType
        return 1 + utf8Bytes(((MqttProperties.StringProperty) mqttProp).value());
    }

    private static <T> int sizeResponseTopic(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x08) ResponseTopic
        return 1 + utf8Bytes(((MqttProperties.StringProperty) mqttProp).value());
    }

    private static <T> int sizeCorrelationData(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.BinaryProperty;
        // 1 byte for encoding propertyId: (0x09) CorrelationData
        return 1 + sizeBinary(((MqttProperties.BinaryProperty) mqttProp).value());
    }

    private static <T> int sizeSubscriptionIdentifier(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x0B) SubscriptionIdentifier
        return 1 + varIntBytes(((MqttProperties.IntegerProperty) mqttProp).value());
    }

    private static <T> int sizeSessionExpiryInterval(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x11) SessionExpiryInterval
        // 4 bytes for encoding SEI value
        return 5;
    }

    private static <T> int sizeAssignedIdentifier(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x12) AssignedIdentifier
        return 1 + utf8Bytes(((MqttProperties.StringProperty) mqttProp).value());
    }

    private static <T> int sizeServerKeepAlive(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x12) AssignedIdentifier
        // 2 bytes for encoding keepAlive value;
        return 3;
    }

    private static <T> int sizeAuthMethod(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x15) AuthenticationMethod
        return 1 + sizeUTF8EncodedString((String) mqttProp.value());
    }

    private static <T> int sizeAuthData(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.BinaryProperty;
        // 1 byte for encoding propertyId: (0x16) AuthenticationData
        return 1 + sizeBinary(((MqttProperties.BinaryProperty) mqttProp).value());
    }

    private static <T> int sizeRequestProblemInformation(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x17) RequestProblemInformation
        // 1 byte for encoding 0|1
        return 2;
    }

    private static <T> int sizeWillDelayInterval(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x18) WillDelayInterval
        // 4 bytes for encoding delay interval value
        return 5;
    }

    private static <T> int sizeRequestResponseInformation(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x19) RequestResponseInformation
        // 1 byte for encoding 0|1
        return 2;
    }

    private static <T> int sizeResponseInformaiton(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x1A) ResponseInformation
        return 1 + utf8Bytes(((MqttProperties.StringProperty) mqttProp).value());
    }

    private static <T> int sizeServerReference(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x1C) ServerReference
        return 1 + utf8Bytes(((MqttProperties.StringProperty) mqttProp).value());
    }

    private static <T> int sizeReasonStringProp(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.StringProperty;
        // 1 byte for encoding propertyId: (0x1F) ReasonString
        return 1 + sizeUTF8EncodedString((String) mqttProp.value());
    }

    private static <T> int sizeReceiveMaximum(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x21) ReceiveMaximum
        // 2 bytes for encoding receive maximum value
        return 3;
    }

    private static <T> int sizeTopicAliasMaximum(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x22) TopicAliasMaximum
        // 2 bytes for encoding topic alias maximum value
        return 3;
    }

    private static <T> int sizeTopicAlias(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x23) TopicAlias
        // 2 bytes for encoding alias value
        return 3;
    }

    private static <T> int sizeMaximumQoS(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x24) MaximumQoS
        // 1 bytes for encoding 0|1 value
        return 2;
    }

    private static <T> int sizeRetainAvailable(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x25) RetainAvailable
        // 1 bytes for encoding 0|1 value
        return 2;
    }

    private static <T> int sizeMaximumPacketSize(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x27) MaximumPacketSize
        // 4 bytes for encoding max packet size value
        return 5;
    }

    private static <T> int sizeWildcardSubscriptionAvailable(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x28) WildcardSubscriptionAvailable
        // 1 byte for encoding 0|1 value
        return 2;
    }

    private static <T> int sizeSubscriptionIdentifierAvailable(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x29) SubscriptionIdentifierAvailable
        // 1 byte for encoding 0|1 value
        return 2;

    }

    private static <T> int sizeSharedSubscriptionAvailable(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.IntegerProperty;
        // 1 byte for encoding propertyId: (0x2A) SharedSubscriptionAvailable
        // 1 byte for encoding 0|1 value
        return 2;
    }

    private static <T> int sizeUserProp(MqttProperties.MqttProperty<T> mqttProp) {
        assert mqttProp instanceof MqttProperties.UserProperties;
        int totalBytes = 0;
        for (MqttProperties.StringPair pair : ((MqttProperties.UserProperties) mqttProp).value()) {
            // 1 byte for encoding propertyId: (0x26) UserProperty
            totalBytes += 1;
            totalBytes += sizeUTF8EncodedString(pair.key);
            totalBytes += sizeUTF8EncodedString(pair.value);
        }
        return totalBytes;
    }

    private static int sizeBinary(byte[] binary) {
        // 2 bytes for encoding size prefix in Binary Data, [MQTT5-1.5.6]
        return 2 + binary.length;
    }

    private static int sizeUTF8EncodedString(String s) {
        int rsBytes = utf8Bytes(s);
        // 2 bytes for encoding size prefix in UTF-8 Encoded String, [MQTT5-1.5.7]
        return 2 + rsBytes;
    }

    private static int varIntBytes(int i) {
        int bytes = 0;
        do {
            int digit = i % 128;
            i /= 128;
            if (i > 0) {
                digit |= 0x80;
            }
            bytes++;
        } while (i > 0);
        return bytes;
    }
}
