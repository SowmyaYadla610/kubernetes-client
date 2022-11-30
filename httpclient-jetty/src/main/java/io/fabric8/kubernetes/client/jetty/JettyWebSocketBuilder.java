/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.jetty;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.http.AbstractBasicBuilder;
import io.fabric8.kubernetes.client.http.Interceptor;
import io.fabric8.kubernetes.client.http.StandardHttpHeaders;
import io.fabric8.kubernetes.client.http.StandardHttpRequest;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.fabric8.kubernetes.client.http.WebSocketHandshakeException;
import io.fabric8.kubernetes.client.utils.Utils;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public class JettyWebSocketBuilder extends AbstractBasicBuilder<JettyWebSocketBuilder> implements WebSocket.Builder {

  private final WebSocketClient webSocketClient;
  private final Duration handshakeTimeout;
  private final Collection<Interceptor> interceptors;
  private String subprotocol;

  public JettyWebSocketBuilder(
      WebSocketClient webSocketClient, Duration handshakeTimeout, Collection<Interceptor> interceptors) {
    this.webSocketClient = webSocketClient;
    this.handshakeTimeout = handshakeTimeout;
    this.interceptors = interceptors;
  }

  @Override
  public CompletableFuture<WebSocket> buildAsync(WebSocket.Listener listener) {
    try {
      webSocketClient.start();
      final var requestBuilder = copy(this);
      interceptors.forEach(i -> i.before(requestBuilder, new StandardHttpHeaders(requestBuilder.getHeaders())));
      final ClientUpgradeRequest cur = new ClientUpgradeRequest();
      if (Utils.isNotNullOrEmpty(requestBuilder.subprotocol)) {
        cur.setSubProtocols(requestBuilder.subprotocol);
      }
      cur.setHeaders(requestBuilder.getHeaders());
      cur.setTimeout(requestBuilder.handshakeTimeout.toMillis(), TimeUnit.MILLISECONDS);
      // Extra-future required because we can't Map the UpgradeException to a WebSocketHandshakeException easily
      final CompletableFuture<WebSocket> future = new CompletableFuture<>();
      final var webSocket = new JettyWebSocket(listener);
      return webSocketClient.connect(webSocket, Objects.requireNonNull(WebSocket.toWebSocketUri(requestBuilder.getUri())), cur)
          .thenApply(s -> webSocket)
          .exceptionally(ex -> {
            if (ex instanceof CompletionException && ex.getCause() instanceof UpgradeException) {
              future.completeExceptionally(toHandshakeException((UpgradeException) ex.getCause()));
            } else if (ex instanceof UpgradeException) {
              future.completeExceptionally(toHandshakeException((UpgradeException) ex));
            } else {
              future.completeExceptionally(ex);
            }
            return null;
          })
          .thenCompose(ws -> {
            future.complete(ws);
            return future;
          });
    } catch (Exception e) {
      throw KubernetesClientException.launderThrowable(e);
    }
  }

  @Override
  public JettyWebSocketBuilder subprotocol(String protocol) {
    this.subprotocol = protocol;
    return this;
  }

  private static WebSocketHandshakeException toHandshakeException(UpgradeException ex) {
    return new WebSocketHandshakeException(new JettyHttpResponse<>(
        new StandardHttpRequest.Builder().uri(ex.getRequestURI()).build(),
        new HttpResponse(null, Collections.emptyList()).status(ex.getResponseStatusCode()),
        null))
            .initCause(ex);
  }

  private static JettyWebSocketBuilder copy(JettyWebSocketBuilder original) {
    final var copy = new JettyWebSocketBuilder(
        original.webSocketClient, original.handshakeTimeout, new ArrayList<>(original.interceptors));
    copy.uri(original.getUri());
    original.getHeaders().forEach((h, values) -> values.forEach(v -> copy.header(h, v)));
    copy.subprotocol(original.subprotocol);
    return copy;
  }
}
