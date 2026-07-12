package com.quizopia.backend.realtime.authorization;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Day 7 has no client command over WebSocket — all mutations go through REST. This guard rejects
 * <b>every</b> inbound client {@code SEND} and every client-crafted {@code MESSAGE} frame, regardless
 * of destination (it does not merely block {@code /topic/**}). Rejects before any controller, broker
 * broadcast, service mutation, or event publication.
 *
 * <p>{@code CONNECT}/{@code SUBSCRIBE}/{@code DISCONNECT} are unaffected.
 */
@Component
public class StompSendGuardInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.SEND || command == StompCommand.MESSAGE) {
            throw new MessagingException("Client SEND/MESSAGE is not allowed");
        }
        return message;
    }
}
