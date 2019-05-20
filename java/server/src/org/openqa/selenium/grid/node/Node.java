// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.grid.node;

import static org.openqa.selenium.grid.web.Routes.combine;
import static org.openqa.selenium.grid.web.Routes.delete;
import static org.openqa.selenium.grid.web.Routes.get;
import static org.openqa.selenium.grid.web.Routes.matching;
import static org.openqa.selenium.grid.web.Routes.post;
import static org.openqa.selenium.remote.HttpSessionId.getSessionId;
import static org.openqa.selenium.remote.http.Contents.utf8String;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.grid.component.HealthCheck;
import org.openqa.selenium.grid.data.CreateSessionRequest;
import org.openqa.selenium.grid.data.CreateSessionResponse;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.web.CommandHandler;
import org.openqa.selenium.grid.web.HandlerNotFoundException;
import org.openqa.selenium.grid.web.Routes;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.DistributedTracer;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A place where individual webdriver sessions are running. Those sessions may be in-memory, or
 * only reachable via localhost and a network. Or they could be something else entirely.
 * <p>
 * This class responds to the following URLs:
 * <table summary="HTTP commands the Node understands">
 * <tr>
 * <th>Verb</th>
 * <th>URL Template</th>
 * <th>Meaning</th>
 * </tr>
 * <tr>
 * <td>POST</td>
 * <td>/se/grid/node/session</td>
 * <td>Attempts to start a new session for the given node. The posted data should be a
 * json-serialized {@link Capabilities} instance. Returns a serialized {@link Session}.
 * Subclasses of {@code Node} are expected to register the session with the
 * {@link org.openqa.selenium.grid.sessionmap.SessionMap}.</td>
 * </tr>
 * <tr>
 * <td>GET</td>
 * <td>/se/grid/node/session/{sessionId}</td>
 * <td>Finds the {@link Session} identified by {@code sessionId} and returns the JSON-serialized
 * form.</td>
 * </tr>
 * <tr>
 * <td>DELETE</td>
 * <td>/se/grid/node/session/{sessionId}</td>
 * <td>Stops the {@link Session} identified by {@code sessionId}. It is expected that this will
 * also cause the session to removed from the
 * {@link org.openqa.selenium.grid.sessionmap.SessionMap}.</td>
 * </tr>
 * <tr>
 * <td>GET</td>
 * <td>/se/grid/node/owner/{sessionId}</td>
 * <td>Allows the node to be queried about whether or not it owns the {@link Session} identified
 * by {@code sessionId}. This returns a boolean.</td>
 * </tr>
 * <tr>
 * <td>*</td>
 * <td>/session/{sessionId}/*</td>
 * <td>The request is forwarded to the {@link Session} identified by {@code sessionId}. When the
 * Quit command is called, the {@link Session} should remove itself from the
 * {@link org.openqa.selenium.grid.sessionmap.SessionMap}.</td>
 * </tr>
 * </table>
 */
public abstract class Node implements Predicate<HttpRequest>, CommandHandler {

  protected final DistributedTracer tracer;
  private final UUID id;
  private final URI uri;
  private final Routes routes;

  protected Node(DistributedTracer tracer, UUID id, URI uri) {
    this.tracer = Objects.requireNonNull(tracer);
    this.id = Objects.requireNonNull(id);
    this.uri = Objects.requireNonNull(uri);

    Json json = new Json();
    routes = combine(
        // "getSessionId" is aggressive about finding session ids, so this needs to be the last
        // route the is checked.
        matching(req -> getSessionId(req.getUri()).map(SessionId::new).map(this::isSessionOwner).orElse(false))
            .using(() -> new ForwardWebDriverCommand(this)),
        get("/se/grid/node/owner/{sessionId}")
            .using((params) -> new IsSessionOwner(this, json, new SessionId(params.get("sessionId")))),
        delete("/se/grid/node/session/{sessionId}")
            .using((params) -> new StopNodeSession(this, new SessionId(params.get("sessionId")))),
        get("/se/grid/node/session/{sessionId}")
            .using((params) -> new GetNodeSession(this, json, new SessionId(params.get("sessionId")))),
        post("/se/grid/node/session").using(() -> new NewNodeSession(this, json)),
        get("/se/grid/node/status")
            .using((req, res) -> res.setContent(utf8String(json.toJson(getStatus())))),
        get("/status").using(() -> new StatusHandler(this, json))
    ).build();
  }

  public UUID getId() {
    return id;
  }

  public URI getUri() {
    return uri;
  }

  public abstract Optional<CreateSessionResponse> newSession(CreateSessionRequest sessionRequest);

  public abstract void executeWebDriverCommand(HttpRequest req, HttpResponse resp);

  public abstract Session getSession(SessionId id) throws NoSuchSessionException;

  public abstract void stop(SessionId id) throws NoSuchSessionException;

  protected abstract boolean isSessionOwner(SessionId id);

  public abstract boolean isSupporting(Capabilities capabilities);

  public abstract NodeStatus getStatus();

  public abstract HealthCheck getHealthCheck();

  @Override
  public boolean test(HttpRequest req) {
    return routes.match(req).isPresent();
  }

  @Override
  public void execute(HttpRequest req, HttpResponse resp) throws IOException {
    Optional<CommandHandler> handler = routes.match(req);
    if (!handler.isPresent()) {
      throw new HandlerNotFoundException(req);
    }
    handler.get().execute(req, resp);
  }
}
