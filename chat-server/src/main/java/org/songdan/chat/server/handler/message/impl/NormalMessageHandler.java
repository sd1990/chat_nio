package org.songdan.chat.server.handler.message.impl;

import lombok.extern.slf4j.Slf4j;
import org.songdan.chat.common.domain.Message;
import org.songdan.chat.common.domain.MessageHeader;
import org.songdan.chat.common.domain.Response;
import org.songdan.chat.common.domain.ResponseHeader;
import org.songdan.chat.common.enumeration.ResponseType;
import org.songdan.chat.common.util.ProtoStuffUtil;
import org.songdan.chat.server.handler.message.MessageHandler;
import org.songdan.chat.server.property.PromptMsgProperty;
import org.songdan.chat.server.user.UserManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by SinjinSong on 2017/5/23.
 */
@Component("MessageHandler.normal")
@Slf4j
public class NormalMessageHandler extends MessageHandler {
    @Autowired
    private UserManager userManager;

    @Override
    public void handle(Message message, Selector server, SelectionKey client, AtomicInteger onlineUsers) {
        try {
            SocketChannel clientChannel = (SocketChannel) client.channel();
            MessageHeader header = message.getHeader();
            SocketChannel receiverChannel = userManager.getUserChannel(header.getReceiver());
            if (receiverChannel == null) {
                //接收者下线
                byte[] response = ProtoStuffUtil.serialize(
                        new Response(
                                ResponseHeader.builder()
                                        .type(ResponseType.PROMPT)
                                        .sender(message.getHeader().getSender())
                                        .timestamp(message.getHeader().getTimestamp())
                                        .build(),
                                PromptMsgProperty.RECEIVER_LOGGED_OFF.getBytes(PromptMsgProperty.charset)));
                clientChannel.write(ByteBuffer.wrap(response));
            } else {
                byte[] response = ProtoStuffUtil.serialize(
                        new Response(
                                ResponseHeader.builder()
                                        .type(ResponseType.NORMAL)
                                        .sender(message.getHeader().getSender())
                                        .timestamp(message.getHeader().getTimestamp())
                                        .build(),
                                message.getBody()));
                log.info("已转发给", receiverChannel);
                receiverChannel.write(ByteBuffer.wrap(response));
                //也给自己发送一份
                clientChannel.write(ByteBuffer.wrap(response));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
