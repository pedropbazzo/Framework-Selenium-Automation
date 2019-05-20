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

package org.openqa.selenium.grid.server;

import static org.openqa.selenium.grid.web.Routes.combine;

import org.openqa.selenium.UnsupportedCommandException;
import org.openqa.selenium.grid.web.CommandHandler;
import org.openqa.selenium.grid.web.Routes;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class CommandHandlerServlet extends HttpServlet {

  private final Routes routes;

  public CommandHandlerServlet(Routes routes) {
    Objects.requireNonNull(routes);
    this.routes = combine(routes)
        .fallbackTo(
            new W3CCommandHandler(
                (req, res) -> {
                  throw new UnsupportedCommandException(String.format(
                      "Unknown command: (%s) %s", req.getMethod(), req.getUri()));
                })).build();
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    HttpRequest request = new ServletRequestWrappingHttpRequest(req);
    HttpResponse response = new ServletResponseWrappingHttpResponse(resp);

    Optional<CommandHandler> possibleMatch = routes.match(request);
    if (possibleMatch.isPresent()) {
      possibleMatch.get().execute(request, response);
    } else {
      throw new IllegalStateException("It should not be possible to get here");
    }
  }
}

