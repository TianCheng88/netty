/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.codec.mqtt;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.mqtt.MqttDecoder.DecoderState;
import io.netty.util.CharsetUtil;

import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.mqtt.MqttCodecUtil.isValidClientId;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.isValidMessageId;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.isValidPublishTopicName;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.resetUnusedFields;
import static io.netty.handler.codec.mqtt.MqttCodecUtil.validateFixedHeader;
import static io.netty.handler.codec.mqtt.MqttSubscriptionOption.RetainedHandlingPolicy;

/**
 * Decodes Mqtt messages from bytes, following
 * the MQTT protocol specification
 * <a href="http://public.dhe.ibm.com/software/dw/webservices/ws-mqtt/mqtt-v3r1.html">v3.1</a>
 * or
 * <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html">v5.0</a>, depending on the
 * version specified in the CONNECT message that first goes through the channel.
 */
public final class MqttDecoder extends ReplayingDecoder<DecoderState> {

    private static final int DEFAULT_MAX_BYTES_IN_MESSAGE = 8092;

    /**
     * States of the decoder.
     * We start at READ_FIXED_HEADER, followed by
     * READ_VARIABLE_HEADER and finally READ_PAYLOAD.
     */
    enum DecoderState {
        READ_FIXED_HEADER,
        READ_VARIABLE_HEADER,
        READ_PAYLOAD,
        BAD_MESSAGE,
    }

    private MqttFixedHeader mqttFixedHeader;
    private Object variableHeader;
    private int bytesRemainingInVariablePart;

    private final int maxBytesInMessage;

    public MqttDecoder() {
      this(DEFAULT_MAX_BYTES_IN_MESSAGE);
    }

    public MqttDecoder(int maxBytesInMessage) {
        super(DecoderState.READ_FIXED_HEADER);
        this.maxBytesInMessage = maxBytesInMessage;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        switch (state()) {
            case READ_FIXED_HEADER: try {
                mqttFixedHeader = decodeFixedHeader(ctx, buffer);
                bytesRemainingInVariablePart = mqttFixedHeader.remainingLength();
                checkpoint(DecoderState.READ_VARIABLE_HEADER);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_VARIABLE_HEADER:  try {
                final Result<?> decodedVariableHeader = decodeVariableHeader(ctx, buffer, mqttFixedHeader);
                variableHeader = decodedVariableHeader.value;
                if (bytesRemainingInVariablePart > maxBytesInMessage) {
                    throw new TooLongFrameException("too large message: " + bytesRemainingInVariablePart + " bytes");
                }
                bytesRemainingInVariablePart -= decodedVariableHeader.numberOfBytesConsumed;
                checkpoint(DecoderState.READ_PAYLOAD);
                // fall through
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case READ_PAYLOAD: try {
                final Result<?> decodedPayload =
                        decodePayload(
                                ctx,
                                buffer,
                                mqttFixedHeader.messageType(),
                                bytesRemainingInVariablePart,
                                variableHeader);
                bytesRemainingInVariablePart -= decodedPayload.numberOfBytesConsumed;
                if (bytesRemainingInVariablePart != 0) {
                    throw new DecoderException(
                            "non-zero remaining payload bytes: " +
                                    bytesRemainingInVariablePart + " (" + mqttFixedHeader.messageType() + ')');
                }
                checkpoint(DecoderState.READ_FIXED_HEADER);
                MqttMessage message = MqttMessageFactory.newMessage(
                        mqttFixedHeader, variableHeader, decodedPayload.value);
                mqttFixedHeader = null;
                variableHeader = null;
                out.add(message);
                break;
            } catch (Exception cause) {
                out.add(invalidMessage(cause));
                return;
            }

            case BAD_MESSAGE:
                // Keep discarding until disconnection.
                buffer.skipBytes(actualReadableBytes());
                break;

            default:
                // Shouldn't reach here.
                throw new Error();
        }
    }

    private MqttMessage invalidMessage(Throwable cause) {
      checkpoint(DecoderState.BAD_MESSAGE);
      return MqttMessageFactory.newInvalidMessage(mqttFixedHeader, variableHeader, cause);
    }

    /**
     * Decodes the fixed header. It's one byte for the flags and then variable bytes for the remaining length.
     *
     * @param buffer the buffer to decode from
     * @return the fixed header
     */
    private static MqttFixedHeader decodeFixedHeader(ChannelHandlerContext ctx, ByteBuf buffer) {
        short b1 = buffer.readUnsignedByte();

        MqttMessageType messageType = MqttMessageType.valueOf(b1 >> 4);
        boolean dupFlag = (b1 & 0x08) == 0x08;
        int qosLevel = (b1 & 0x06) >> 1;
        boolean retain = (b1 & 0x01) != 0;

        int remainingLength = 0;
        int multiplier = 1;
        short digit;
        int loops = 0;
        do {
            digit = buffer.readUnsignedByte();
            remainingLength += (digit & 127) * multiplier;
            multiplier *= 128;
            loops++;
        } while ((digit & 128) != 0 && loops < 4);

        // MQTT protocol limits Remaining Length to 4 bytes
        if (loops == 4 && (digit & 128) != 0) {
            throw new DecoderException("remaining length exceeds 4 digits (" + messageType + ')');
        }
        MqttFixedHeader decodedFixedHeader =
                new MqttFixedHeader(messageType, dupFlag, MqttQoS.valueOf(qosLevel), retain, remainingLength);
        return validateFixedHeader(ctx, resetUnusedFields(decodedFixedHeader));
    }

    /**
     * Decodes the variable header (if any)
     * @param buffer the buffer to decode from
     * @param mqttFixedHeader MqttFixedHeader of the same message
     * @return the variable header
     */
    private Result<?> decodeVariableHeader(ChannelHandlerContext ctx, ByteBuf buffer, MqttFixedHeader mqttFixedHeader) {
        switch (mqttFixedHeader.messageType()) {
            case CONNECT:
                return decodeConnectionVariableHeader(ctx, buffer);

            case CONNACK:
                return decodeConnAckVariableHeader(ctx, buffer);

            case UNSUBSCRIBE:
            case SUBSCRIBE:
            case SUBACK:
            case UNSUBACK:
                return decodeMessageIdAndPropertiesVariableHeader(ctx, buffer);

            case PUBACK:
            case PUBREC:
            case PUBCOMP:
            case PUBREL:
                return decodePubReplyMessage(buffer);

            case PUBLISH:
                return decodePublishVariableHeader(ctx, buffer, mqttFixedHeader);

            case DISCONNECT:
            case AUTH:
                return decodeReasonCodeAndPropertiesVariableHeader(buffer);

            case PINGREQ:
            case PINGRESP:
                // Empty variable header
                return new Result<Object>(null, 0);
            default:
                //shouldn't reach here
                throw new DecoderException("Unknown message type: " + mqttFixedHeader.messageType());
        }
    }

    private static Result<MqttConnectVariableHeader> decodeConnectionVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer) {
        final Result<String> protoString = decodeString(buffer);
        int numberOfBytesConsumed = protoString.numberOfBytesConsumed;

        final byte protocolLevel = buffer.readByte();
        numberOfBytesConsumed += 1;

        MqttVersion version = MqttVersion.fromProtocolNameAndLevel(protoString.value, protocolLevel);
        MqttCodecUtil.setMqttVersion(ctx, version);

        final int b1 = buffer.readUnsignedByte();
        numberOfBytesConsumed += 1;

        final Result<Integer> keepAlive = decodeMsbLsb(buffer);
        numberOfBytesConsumed += keepAlive.numberOfBytesConsumed;

        final boolean hasUserName = (b1 & 0x80) == 0x80;
        final boolean hasPassword = (b1 & 0x40) == 0x40;
        final boolean willRetain = (b1 & 0x20) == 0x20;
        final int willQos = (b1 & 0x18) >> 3;
        final boolean willFlag = (b1 & 0x04) == 0x04;
        final boolean cleanSession = (b1 & 0x02) == 0x02;
        if (version == MqttVersion.MQTT_3_1_1 || version == MqttVersion.MQTT_5) {
            final boolean zeroReservedFlag = (b1 & 0x01) == 0x0;
            if (!zeroReservedFlag) {
                // MQTT v3.1.1: The Server MUST validate that the reserved flag in the CONNECT Control Packet is
                // set to zero and disconnect the Client if it is not zero.
                // See http://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html#_Toc385349230
                throw new DecoderException("non-zero reserved flag");
            }
        }

        final MqttProperties properties;
        if (version == MqttVersion.MQTT_5) {
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
        } else {
            properties = MqttProperties.NO_PROPERTIES;
        }

        final MqttConnectVariableHeader mqttConnectVariableHeader = new MqttConnectVariableHeader(
                version.protocolName(),
                version.protocolLevel(),
                hasUserName,
                hasPassword,
                willRetain,
                willQos,
                willFlag,
                cleanSession,
                keepAlive.value,
                properties);
        return new Result<MqttConnectVariableHeader>(mqttConnectVariableHeader, numberOfBytesConsumed);
    }

    private static Result<MqttConnAckVariableHeader> decodeConnAckVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer) {
        final MqttVersion mqttVersion = MqttCodecUtil.getMqttVersion(ctx);
        final boolean sessionPresent = (buffer.readUnsignedByte() & 0x01) == 0x01;
        byte returnCode = buffer.readByte();
        int numberOfBytesConsumed = 2;

        final MqttProperties properties;
        if (mqttVersion == MqttVersion.MQTT_5) {
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
        } else {
            properties = MqttProperties.NO_PROPERTIES;
        }

        final MqttConnAckVariableHeader mqttConnAckVariableHeader =
                new MqttConnAckVariableHeader(MqttConnectReturnCode.valueOf(returnCode), sessionPresent, properties);
        return new Result<MqttConnAckVariableHeader>(mqttConnAckVariableHeader, numberOfBytesConsumed);
    }

    private static Result<MqttMessageIdVariableHeader> decodeMessageIdVariableHeader(ByteBuf buffer) {
        final Result<Integer> messageId = decodeMessageId(buffer);
        return new Result<MqttMessageIdVariableHeader>(
                MqttMessageIdVariableHeader.from(messageId.value),
                messageId.numberOfBytesConsumed);
    }

    private static Result<MqttMessageIdAndPropertiesVariableHeader> decodeMessageIdAndPropertiesVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer) {
        final MqttVersion mqttVersion = MqttCodecUtil.getMqttVersion(ctx);
        final Result<Integer> packetId = decodeMessageId(buffer);

        final MqttMessageIdAndPropertiesVariableHeader mqttVariableHeader;
        final int mqtt5Consumed;

        if (mqttVersion == MqttVersion.MQTT_5) {
            final Result<MqttProperties> properties = decodeProperties(buffer);
            mqttVariableHeader = new MqttMessageIdAndPropertiesVariableHeader(packetId.value, properties.value);
            mqtt5Consumed = properties.numberOfBytesConsumed;
        } else {
            mqttVariableHeader = new MqttMessageIdAndPropertiesVariableHeader(packetId.value,
                    MqttProperties.NO_PROPERTIES);
            mqtt5Consumed = 0;
        }

        return new Result<MqttMessageIdAndPropertiesVariableHeader>(mqttVariableHeader,
                packetId.numberOfBytesConsumed + mqtt5Consumed);
    }

    private Result<MqttPubReplyMessageVariableHeader> decodePubReplyMessage(ByteBuf buffer) {
        final Result<Integer> packetId = decodeMessageId(buffer);

        final MqttPubReplyMessageVariableHeader mqttPubAckVariableHeader;
        final int consumed;
        if (bytesRemainingInVariablePart > 3) {
            final byte reasonCode = buffer.readByte();
            final Result<MqttProperties> properties = decodeProperties(buffer);
            mqttPubAckVariableHeader = new MqttPubReplyMessageVariableHeader(packetId.value,
                    reasonCode,
                    properties.value);
            consumed = packetId.numberOfBytesConsumed + 1 + properties.numberOfBytesConsumed;
        } else if (bytesRemainingInVariablePart > 2) {
            final byte reasonCode = buffer.readByte();
            mqttPubAckVariableHeader = new MqttPubReplyMessageVariableHeader(packetId.value,
                    reasonCode,
                    MqttProperties.NO_PROPERTIES);
            consumed = packetId.numberOfBytesConsumed + 1;
        } else {
            mqttPubAckVariableHeader = new MqttPubReplyMessageVariableHeader(packetId.value,
                    (byte) 0,
                    MqttProperties.NO_PROPERTIES);
            consumed = packetId.numberOfBytesConsumed;
        }

        return new Result<MqttPubReplyMessageVariableHeader>(mqttPubAckVariableHeader, consumed);
    }

    private Result<MqttReasonCodeAndPropertiesVariableHeader> decodeReasonCodeAndPropertiesVariableHeader(
            ByteBuf buffer) {
        final byte reasonCode;
        final MqttProperties properties;
        final int consumed;
        if (bytesRemainingInVariablePart > 1) {
            reasonCode = buffer.readByte();
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            consumed = 1 + propertiesResult.numberOfBytesConsumed;
        } else if (bytesRemainingInVariablePart > 0) {
            reasonCode = buffer.readByte();
            properties = MqttProperties.NO_PROPERTIES;
            consumed = 1;
        } else {
            reasonCode = 0;
            properties = MqttProperties.NO_PROPERTIES;
            consumed = 0;
        }
        final MqttReasonCodeAndPropertiesVariableHeader mqttReasonAndPropsVariableHeader =
                new MqttReasonCodeAndPropertiesVariableHeader(reasonCode, properties);

        return new Result<MqttReasonCodeAndPropertiesVariableHeader>(
                mqttReasonAndPropsVariableHeader,
                consumed);
    }

    private Result<MqttPublishVariableHeader> decodePublishVariableHeader(
            ChannelHandlerContext ctx,
            ByteBuf buffer,
            MqttFixedHeader mqttFixedHeader) {
        final MqttVersion mqttVersion = MqttCodecUtil.getMqttVersion(ctx);
        final Result<String> decodedTopic = decodeString(buffer);
        if (!isValidPublishTopicName(decodedTopic.value)) {
            throw new DecoderException("invalid publish topic name: " + decodedTopic.value + " (contains wildcards)");
        }
        int numberOfBytesConsumed = decodedTopic.numberOfBytesConsumed;

        int messageId = -1;
        if (mqttFixedHeader.qosLevel().value() > 0) {
            final Result<Integer> decodedMessageId = decodeMessageId(buffer);
            messageId = decodedMessageId.value;
            numberOfBytesConsumed += decodedMessageId.numberOfBytesConsumed;
        }

        final MqttProperties properties;
        if (mqttVersion == MqttVersion.MQTT_5) {
            final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
            properties = propertiesResult.value;
            numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
        } else {
            properties = MqttProperties.NO_PROPERTIES;
        }

        final MqttPublishVariableHeader mqttPublishVariableHeader =
                new MqttPublishVariableHeader(decodedTopic.value, messageId, properties);
        return new Result<MqttPublishVariableHeader>(mqttPublishVariableHeader, numberOfBytesConsumed);
    }

    private static Result<Integer> decodeMessageId(ByteBuf buffer) {
        final Result<Integer> messageId = decodeMsbLsb(buffer);
        if (!isValidMessageId(messageId.value)) {
            throw new DecoderException("invalid messageId: " + messageId.value);
        }
        return messageId;
    }

    /**
     * Decodes the payload.
     *
     * @param buffer the buffer to decode from
     * @param messageType  type of the message being decoded
     * @param bytesRemainingInVariablePart bytes remaining
     * @param variableHeader variable header of the same message
     * @return the payload
     */
    private static Result<?> decodePayload(
            ChannelHandlerContext ctx,
            ByteBuf buffer,
            MqttMessageType messageType,
            int bytesRemainingInVariablePart,
            Object variableHeader) {
        switch (messageType) {
            case CONNECT:
                return decodeConnectionPayload(buffer, (MqttConnectVariableHeader) variableHeader);

            case SUBSCRIBE:
                return decodeSubscribePayload(buffer, bytesRemainingInVariablePart);

            case SUBACK:
                return decodeSubackPayload(buffer, bytesRemainingInVariablePart);

            case UNSUBSCRIBE:
                return decodeUnsubscribePayload(buffer, bytesRemainingInVariablePart);

            case UNSUBACK:
                return decodeUnsubAckPayload(ctx, buffer, bytesRemainingInVariablePart);

            case PUBLISH:
                return decodePublishPayload(buffer, bytesRemainingInVariablePart);

            default:
                // unknown payload , no byte consumed
                return new Result<Object>(null, 0);
        }
    }

    private static Result<MqttConnectPayload> decodeConnectionPayload(
            ByteBuf buffer,
            MqttConnectVariableHeader mqttConnectVariableHeader) {
        final Result<String> decodedClientId = decodeString(buffer);
        final String decodedClientIdValue = decodedClientId.value;
        final MqttVersion mqttVersion = MqttVersion.fromProtocolNameAndLevel(mqttConnectVariableHeader.name(),
                (byte) mqttConnectVariableHeader.version());
        if (!isValidClientId(mqttVersion, decodedClientIdValue)) {
            throw new MqttIdentifierRejectedException("invalid clientIdentifier: " + decodedClientIdValue);
        }
        int numberOfBytesConsumed = decodedClientId.numberOfBytesConsumed;

        Result<String> decodedWillTopic = null;
        Result<byte[]> decodedWillMessage = null;

        final MqttProperties willProperties;
        if (mqttConnectVariableHeader.isWillFlag()) {
            if (mqttVersion == MqttVersion.MQTT_5) {
                final Result<MqttProperties> propertiesResult = decodeProperties(buffer);
                willProperties = propertiesResult.value;
                numberOfBytesConsumed += propertiesResult.numberOfBytesConsumed;
            } else {
                willProperties = MqttProperties.NO_PROPERTIES;
            }
            decodedWillTopic = decodeString(buffer, 0, 32767);
            numberOfBytesConsumed += decodedWillTopic.numberOfBytesConsumed;
            decodedWillMessage = decodeByteArray(buffer);
            numberOfBytesConsumed += decodedWillMessage.numberOfBytesConsumed;
        } else {
            willProperties = MqttProperties.NO_PROPERTIES;
        }
        Result<String> decodedUserName = null;
        Result<byte[]> decodedPassword = null;
        if (mqttConnectVariableHeader.hasUserName()) {
            decodedUserName = decodeString(buffer);
            numberOfBytesConsumed += decodedUserName.numberOfBytesConsumed;
        }
        if (mqttConnectVariableHeader.hasPassword()) {
            decodedPassword = decodeByteArray(buffer);
            numberOfBytesConsumed += decodedPassword.numberOfBytesConsumed;
        }

        final MqttConnectPayload mqttConnectPayload =
                new MqttConnectPayload(
                        decodedClientId.value,
                        willProperties,
                        decodedWillTopic != null ? decodedWillTopic.value : null,
                        decodedWillMessage != null ? decodedWillMessage.value : null,
                        decodedUserName != null ? decodedUserName.value : null,
                        decodedPassword != null ? decodedPassword.value : null);
        return new Result<MqttConnectPayload>(mqttConnectPayload, numberOfBytesConsumed);
    }

    private static Result<MqttSubscribePayload> decodeSubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<MqttTopicSubscription> subscribeTopics = new ArrayList<MqttTopicSubscription>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            //See 3.8.3.1 Subscription Options of MQTT 5.0 specification for optionByte details
            final short optionByte = buffer.readUnsignedByte();

            MqttQoS qos = MqttQoS.valueOf(optionByte & 0x03);
            boolean noLocal = ((optionByte & 0x04) >> 2) == 1;
            boolean retainAsPublished = ((optionByte & 0x08) >> 3) == 1;
            RetainedHandlingPolicy retainHandling = RetainedHandlingPolicy.valueOf((optionByte & 0x30) >> 4);

            final MqttSubscriptionOption subscriptionOption = new MqttSubscriptionOption(qos,
                    noLocal,
                    retainAsPublished,
                    retainHandling);

            numberOfBytesConsumed++;
            subscribeTopics.add(new MqttTopicSubscription(decodedTopicName.value, subscriptionOption));
        }
        return new Result<MqttSubscribePayload>(new MqttSubscribePayload(subscribeTopics), numberOfBytesConsumed);
    }

    private static Result<MqttSubAckPayload> decodeSubackPayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<Integer> grantedQos = new ArrayList<Integer>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            int qos = buffer.readUnsignedByte();
            if (qos != MqttQoS.FAILURE.value()) {
                qos &= 0x03;
            }
            numberOfBytesConsumed++;
            grantedQos.add(qos);
        }
        return new Result<MqttSubAckPayload>(new MqttSubAckPayload(grantedQos), numberOfBytesConsumed);
    }

    private static Result<MqttUnsubAckPayload> decodeUnsubAckPayload(
        ChannelHandlerContext ctx,
        ByteBuf buffer,
        int bytesRemainingInVariablePart) {
        final List<Short> reasonCodes = new ArrayList<Short>(bytesRemainingInVariablePart);
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            short reasonCode = buffer.readUnsignedByte();
            numberOfBytesConsumed++;
            reasonCodes.add(reasonCode);
        }
        return new Result<MqttUnsubAckPayload>(new MqttUnsubAckPayload(reasonCodes), numberOfBytesConsumed);
    }

    private static Result<MqttUnsubscribePayload> decodeUnsubscribePayload(
            ByteBuf buffer,
            int bytesRemainingInVariablePart) {
        final List<String> unsubscribeTopics = new ArrayList<String>();
        int numberOfBytesConsumed = 0;
        while (numberOfBytesConsumed < bytesRemainingInVariablePart) {
            final Result<String> decodedTopicName = decodeString(buffer);
            numberOfBytesConsumed += decodedTopicName.numberOfBytesConsumed;
            unsubscribeTopics.add(decodedTopicName.value);
        }
        return new Result<MqttUnsubscribePayload>(
                new MqttUnsubscribePayload(unsubscribeTopics),
                numberOfBytesConsumed);
    }

    private static Result<ByteBuf> decodePublishPayload(ByteBuf buffer, int bytesRemainingInVariablePart) {
        ByteBuf b = buffer.readRetainedSlice(bytesRemainingInVariablePart);
        return new Result<ByteBuf>(b, bytesRemainingInVariablePart);
    }

    private static Result<String> decodeString(ByteBuf buffer) {
        return decodeString(buffer, 0, Integer.MAX_VALUE);
    }

    private static Result<String> decodeString(ByteBuf buffer, int minBytes, int maxBytes) {
        final Result<Integer> decodedSize = decodeMsbLsb(buffer);
        int size = decodedSize.value;
        int numberOfBytesConsumed = decodedSize.numberOfBytesConsumed;
        if (size < minBytes || size > maxBytes) {
            buffer.skipBytes(size);
            numberOfBytesConsumed += size;
            return new Result<String>(null, numberOfBytesConsumed);
        }
        String s = buffer.toString(buffer.readerIndex(), size, CharsetUtil.UTF_8);
        buffer.skipBytes(size);
        numberOfBytesConsumed += size;
        return new Result<String>(s, numberOfBytesConsumed);
    }

    private static Result<byte[]> decodeByteArray(ByteBuf buffer) {
        final Result<Integer> decodedSize = decodeMsbLsb(buffer);
        int size = decodedSize.value;
        byte[] bytes = new byte[size];
        buffer.readBytes(bytes);
        return new Result<byte[]>(bytes, decodedSize.numberOfBytesConsumed + size);
    }

    private static Result<Integer> decodeMsbLsb(ByteBuf buffer) {
        return decodeMsbLsb(buffer, 0, 65535);
    }

    private static Result<Integer> decodeMsbLsb(ByteBuf buffer, int min, int max) {
        short msbSize = buffer.readUnsignedByte();
        short lsbSize = buffer.readUnsignedByte();
        final int numberOfBytesConsumed = 2;
        int result = msbSize << 8 | lsbSize;
        if (result < min || result > max) {
            result = -1;
        }
        return new Result<Integer>(result, numberOfBytesConsumed);
    }

    /**
     * See 1.5.5 Variable Byte Integer section of MQTT 5.0 specification for encoding/decoding rules
     *
     * @param buffer the buffer to decode from
     * @return decoded integer
     */
    private static Result<Integer> decodeVariableByteInteger(ByteBuf buffer) {
        int remainingLength = 0;
        int multiplier = 1;
        short digit;
        int loops = 0;
        do {
            digit = buffer.readUnsignedByte();
            remainingLength += (digit & 127) * multiplier;
            multiplier *= 128;
            loops++;
        } while ((digit & 128) != 0 && loops < 4);

        // MQTT protocol limits Remaining Length to 4 bytes
        if (loops == 4 && (digit & 128) != 0) {
            return null;
        }
        return new Result<Integer>(remainingLength, loops);
    }

    private static final class Result<T> {

        private final T value;
        private final int numberOfBytesConsumed;

        Result(T value, int numberOfBytesConsumed) {
            this.value = value;
            this.numberOfBytesConsumed = numberOfBytesConsumed;
        }
    }

    private static Result<MqttProperties> decodeProperties(ByteBuf buffer) {
        final Result<Integer> propertiesLength = decodeVariableByteInteger(buffer);
        int totalPropertiesLength = propertiesLength.value;
        int numberOfBytesConsumed = propertiesLength.numberOfBytesConsumed;

        MqttProperties decodedProperties = new MqttProperties();
        while (numberOfBytesConsumed < totalPropertiesLength) {
            Result<Integer> propertyId = decodeVariableByteInteger(buffer);
            numberOfBytesConsumed += propertyId.numberOfBytesConsumed;

            MqttProperties.MqttPropertyType propertyType = MqttProperties.MqttPropertyType.valueOf(propertyId.value);
            switch (propertyType) {
                case PAYLOAD_FORMAT_INDICATOR:
                case REQUEST_PROBLEM_INFORMATION:
                case REQUEST_RESPONSE_INFORMATION:
                case MAXIMUM_QOS:
                case RETAIN_AVAILABLE:
                case WILDCARD_SUBSCRIPTION_AVAILABLE:
                case SUBSCRIPTION_IDENTIFIER_AVAILABLE:
                case SHARED_SUBSCRIPTION_AVAILABLE:
                    final int b1 = buffer.readUnsignedByte();
                    numberOfBytesConsumed++;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, b1));
                    break;
                case SERVER_KEEP_ALIVE:
                case RECEIVE_MAXIMUM:
                case TOPIC_ALIAS_MAXIMUM:
                case TOPIC_ALIAS:
                    final Result<Integer> int2BytesResult = decodeMsbLsb(buffer);
                    numberOfBytesConsumed += int2BytesResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, int2BytesResult.value));
                    break;
                case PUBLICATION_EXPIRY_INTERVAL:
                case SESSION_EXPIRY_INTERVAL:
                case WILL_DELAY_INTERVAL:
                case MAXIMUM_PACKET_SIZE:
                    final int maxPacketSize = buffer.readInt();
                    numberOfBytesConsumed += 4;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, maxPacketSize));
                    break;
                case SUBSCRIPTION_IDENTIFIER:
                    Result<Integer> vbIntegerResult = decodeVariableByteInteger(buffer);
                    numberOfBytesConsumed += vbIntegerResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.IntegerProperty(propertyId.value, vbIntegerResult.value));
                    break;
                case CONTENT_TYPE:
                case RESPONSE_TOPIC:
                case ASSIGNED_CLIENT_IDENTIFIER:
                case AUTHENTICATION_METHOD:
                case RESPONSE_INFORMATION:
                case SERVER_REFERENCE:
                case REASON_STRING:
                    final Result<String> stringResult = decodeString(buffer);
                    numberOfBytesConsumed += stringResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.StringProperty(propertyId.value, stringResult.value));
                    break;
                case USER_PROPERTY:
                    final Result<String> keyResult = decodeString(buffer);
                    final Result<String> valueResult = decodeString(buffer);
                    numberOfBytesConsumed += keyResult.numberOfBytesConsumed;
                    numberOfBytesConsumed += valueResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.UserProperty(keyResult.value, valueResult.value));
                    break;
                case CORRELATION_DATA:
                case AUTHENTICATION_DATA:
                    final Result<byte[]> binaryDataResult = decodeByteArray(buffer);
                    numberOfBytesConsumed += binaryDataResult.numberOfBytesConsumed;
                    decodedProperties.add(new MqttProperties.BinaryProperty(propertyId.value, binaryDataResult.value));
                    break;
                default:
                    //shouldn't reach here
                    throw new DecoderException("Unknown property type: " + propertyType);
            }
        }

        return new Result<MqttProperties>(decodedProperties, numberOfBytesConsumed);
    }
}
