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

package org.openqa.selenium.grid.testing;

import org.openqa.selenium.grid.web.CommandHandler;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.WebSocket;

import java.io.IOException;
import java.net.URL;
import java.util.function.Predicate;

public class PassthroughHttpClient<T extends Predicate<HttpRequest> & CommandHandler>
    implements HttpClient {

  private final T handler;

  public PassthroughHttpClient(T handler) {
    this.handler = handler;
  }

  @Override
  public HttpResponse execute(HttpRequest request) throws IOException {
    if (!handler.test(request)) {
      throw new IOException("Doomed");
    }

    HttpResponse response = new HttpResponse();
    handler.execute(request, response);
    return response;
  }

  @Override
  public WebSocket openSocket(HttpRequest request, WebSocket.Listener listener) {
    throw new UnsupportedOperationException("openSocket");
  }

  public static class Factory<T extends Predicate<HttpRequest> & CommandHandler>
      implements HttpClient.Factory {

    private final T handler;

    public Factory(T handler) {
      this.handler = handler;
    }

    @Override
    public Builder builder() {
      return new Builder() {
        @Override
        public HttpClient createClient(URL url) {
          return new PassthroughHttpClient<>(handler);
        }
      };
    }

    @Override
    public void cleanupIdleClients() {
      // Does nothing
    }
  }
}
