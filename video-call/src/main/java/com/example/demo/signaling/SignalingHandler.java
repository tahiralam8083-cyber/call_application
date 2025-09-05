package com.example.demo.signaling;


import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SignalingHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(SignalingHandler.class);
	private final ObjectMapper objectMapper = new ObjectMapper();

	// roomId -> set of sessions in that room
	private final Map<String, Set<WebSocketSession>> roomIdToSessions = new ConcurrentHashMap<>();

	 @Override
	    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
	        log.info("Received message: {}", message.getPayload());

	        JsonNode node = objectMapper.readTree(message.getPayload());
	        String type = node.path("type").asText();
	        String roomId = node.path("roomId").asText();

	        if (roomId == null || roomId.isEmpty()) {
	            log.warn("Received message with empty roomId. Ignoring...");
	            return;
	        }

	        roomIdToSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
	        log.info("Added session {} to room {}", session.getId(), roomId);

	        switch (type) {
	            case "join" -> {
	                log.info("Session {} joined room {}", session.getId(), roomId);
	                broadcast(roomId, session, wrap("peer-joined", roomId, null));
	            }
	            case "offer" -> {
	                log.info("Received OFFER from session {} in room {}", session.getId(), roomId);
	                broadcast(roomId, session, message.getPayload());
	            }
	            case "answer" -> {
	                log.info("Received ANSWER from session {} in room {}", session.getId(), roomId);
	                broadcast(roomId, session, message.getPayload());
	            }
	            case "ice-candidate" -> {
	                log.info("Received ICE candidate from session {} in room {}", session.getId(), roomId);
	                broadcast(roomId, session, message.getPayload());
	            }
	            case "chat" -> {
	                log.info("Received CHAT message from session {} in room {}", session.getId(), roomId);
	                // Relay chat messages to all peers in the same room (excluding sender)
	                broadcast(roomId, session, message.getPayload());
	            }
	            default -> log.warn("Unknown message type '{}' from session {}", type, session.getId());
	        }
	    }

	    @Override
	    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
	        log.info("Connection closed for session {} (status: {})", session.getId(), status);
	        roomIdToSessions.values().forEach(set -> set.remove(session));
	    }

	    private void broadcast(String roomId, WebSocketSession sender, String payload) throws IOException {
	        Set<WebSocketSession> sessions = roomIdToSessions.get(roomId);
	        if (sessions == null) {
	            log.warn("No active sessions in room {} to broadcast", roomId);
	            return;
	        }
	        for (WebSocketSession s : sessions) {
	            if (s.isOpen() && s != sender) {
	                log.info("Sending message to session {} in room {}", s.getId(), roomId);
	                s.sendMessage(new TextMessage(payload));
	            }
	        }
	    }

	    private String wrap(String type, String roomId, JsonNode data) throws com.fasterxml.jackson.core.JsonProcessingException {
	        return objectMapper.createObjectNode()
	                .put("type", type)
	                .put("roomId", roomId)
	                .set("data", data)
	                .toString();
	    }
}


