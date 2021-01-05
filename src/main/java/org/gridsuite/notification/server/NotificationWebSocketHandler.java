/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.gridsuite.notification.server;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A WebSocketHandler that sends messages from a broker to websockets opened by clients, interleaving with pings to keep connections open.
 *
 * Spring Cloud Stream gets the consumeNotification bean and calls it with the
 * flux from the broker. We call publish and connect to subscribe immediately to the flux
 * and multicast the messages to all connected websockets and to discard the messages when
 * no websockets are connected.
 *
 * @author Jon Harper <jon.harper at rte-france.com>
 */
@Component
public class NotificationWebSocketHandler implements WebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationWebSocketHandler.class);
    private static final String CATEGORY_BROKER_INPUT = NotificationWebSocketHandler.class.getName() + ".messages.input-broker";
    private static final String CATEGORY_WS_OUTPUT = NotificationWebSocketHandler.class.getName() + ".messages.output-websocket";
    private static final String QUERY_STUDY_NAME = "studyName";
    private static final String QUERY_UPDATE_TYPE = "updateType";
    private static final String HEADER_STUDY_NAME = "studyName";
    private static final String HEADER_UPDATE_TYPE = "updateType";
    private static final String HEADER_TIMESTAMP = "timestamp";
    private static final String HEADER_ERROR = "error";
    private static final String HEADER_SUBSTATIONS_IDS = "substationsIds";

    private ObjectMapper jacksonObjectMapper;

    private int heartbeatInterval;

    public NotificationWebSocketHandler(ObjectMapper jacksonObjectMapper, @Value("${notification.websocket.heartbeat.interval:30}") int heartbeatInterval) {
        this.jacksonObjectMapper = jacksonObjectMapper;
        this.heartbeatInterval = heartbeatInterval;
    }

    Flux<Message<String>> flux;

    @Bean
    public Consumer<Flux<Message<String>>> consumeNotification() {
        return f -> {
            ConnectableFlux<Message<String>> c = f.log(CATEGORY_BROKER_INPUT, Level.FINE).publish();
            this.flux = c;
            c.connect();
            // Force connect 1 fake subscriber to consumme messages as they come.
            // Otherwise, reactorcore buffers some messages (not until the connectable flux had
            // at least one subscriber. Is there a better way ?
            c.subscribe();
        };
    }

    /**
     * map from the broker flux to the filtered flux for one websocket client, extracting only relevant fields.
     */
    private Flux<WebSocketMessage> notificationFlux(WebSocketSession webSocketSession,
                                                    String filterStudyName,
                                                    String filterUpdateType) {
        return flux.transform(f -> {
            Flux<Message<String>> res = f;
            if (filterStudyName != null) {
                res = res.filter(m -> filterStudyName.equals(m.getHeaders().get(HEADER_STUDY_NAME)));
            }
            if (filterUpdateType != null) {
                res = res.filter(m -> filterUpdateType.equals(m.getHeaders().get(HEADER_UPDATE_TYPE)));
            }
            return res;
        }).map(m -> {
            try {
                Map<String, Object> headers = new HashMap<>();
                headers.put(HEADER_TIMESTAMP, m.getHeaders().get(HEADER_TIMESTAMP));
                headers.put(HEADER_STUDY_NAME, m.getHeaders().get(HEADER_STUDY_NAME));
                headers.put(HEADER_UPDATE_TYPE, m.getHeaders().get(HEADER_UPDATE_TYPE));
                headers.put(HEADER_ERROR, m.getHeaders().get(HEADER_ERROR));
                if (m.getHeaders().get(HEADER_SUBSTATIONS_IDS) != null) {
                    headers.put(HEADER_SUBSTATIONS_IDS, m.getHeaders().get(HEADER_SUBSTATIONS_IDS));
                }
                Map<String, Object> submap = Map.of(
                        "payload", m.getPayload(),
                        "headers", headers);
                return jacksonObjectMapper.writeValueAsString(submap);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }).log(CATEGORY_WS_OUTPUT, Level.FINE).map(webSocketSession::textMessage);
    }

    /**
     * A heartbeat flux sending websockets pings
     */
    private Flux<WebSocketMessage> heartbeatFlux(WebSocketSession webSocketSession) {
        return Flux.interval(Duration.ofSeconds(heartbeatInterval)).map(n -> webSocketSession
                .pingMessage(dbf -> dbf.wrap((webSocketSession.getId() + "-" + n).getBytes(StandardCharsets.UTF_8))));
    }

    @Override
    public Mono<Void> handle(WebSocketSession webSocketSession) {
        URI uri = webSocketSession.getHandshakeInfo().getUri();
        MultiValueMap<String, String> parameters = UriComponentsBuilder.fromUri(uri).build(true).getQueryParams();
        String filterStudyName = parameters.getFirst(QUERY_STUDY_NAME);
        if (filterStudyName != null) {
            try {
                filterStudyName = URLDecoder.decode(filterStudyName, StandardCharsets.UTF_8.toString());
            } catch (UnsupportedEncodingException e) {
                throw new NotificationServerRuntimeException(e.getMessage());
            }
        }
        String filterUpdateType = parameters.getFirst(QUERY_UPDATE_TYPE);
        LOGGER.debug("New websocket connection for studyName={}, updateType={}", filterStudyName, filterUpdateType);
        return webSocketSession
                .send(notificationFlux(webSocketSession, filterStudyName, filterUpdateType)
                        .mergeWith(heartbeatFlux(webSocketSession)))
                .and(webSocketSession.receive());
    }
}
