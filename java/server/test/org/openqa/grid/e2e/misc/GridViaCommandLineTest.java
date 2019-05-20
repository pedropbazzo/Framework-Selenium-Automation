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

package org.openqa.grid.e2e.misc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.openqa.selenium.remote.http.Contents.string;

import com.google.common.base.Function;

import org.junit.After;
import org.junit.Test;
import org.openqa.grid.e2e.utils.GridTestHelper;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.selenium.GridLauncherV3;
import org.openqa.grid.shared.Stoppable;
import org.openqa.grid.web.Hub;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.net.UrlChecker;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.server.SeleniumServer;
import org.openqa.selenium.support.ui.FluentWait;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Ensure that launching the hub / node in most common ways simulating command line args works
 */
public class GridViaCommandLineTest {

  private Stoppable server = ()->{};
  private Stoppable node = ()->{};

  @After
  public void stopServer() {
    server.stop();
    node.stop();
  }

  @Test
  public void unrecognizedRole() {
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-role", "hamlet"};
    new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertThat(outSpy.toString())
        .startsWith("Error: the role 'hamlet' does not match a recognized server role");
  }

  @Test
  public void canPrintVersion() {
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-version"};
    new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertThat(outSpy.toString()).startsWith("Selenium server version: ");
  }

  @Test
  public void canPrintGeneralHelp() {
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-help"};
    new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertThat(outSpy.toString()).startsWith("Usage: <main class> [options]").contains("-role");
  }

  @Test
  public void canPrintHubHelp() {
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-role", "hub", "-help"};
    new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertThat(outSpy.toString()).startsWith("Usage: <main class> [options]").contains("-hubConfig");
  }

  @Test
  public void canPrintNodeHelp() {
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-role", "node", "-help"};
    new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertThat(outSpy.toString()).startsWith("Usage: <main class> [options]").contains("-nodeConfig");
  }

  @Test
  public void canRedirectLogToFile() throws Exception {
    int port = PortProber.findFreePort();
    Path tempLog = Files.createTempFile("test", ".log");
    String[] args = {"-log", tempLog.toString(), "-port", Integer.toString(port)};

    server = new GridLauncherV3().launch(args);
    assertNotNull(server);
    waitUntilServerIsAvailableOnPort(port);

    String log = String.join("", Files.readAllLines(tempLog));
    assertThat(log).contains("Selenium Server is up and running on port " + port);
  }

  @Test
  public void canLaunchStandalone() throws Exception {
    int port = PortProber.findFreePort();
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-role", "standalone", "-port", Integer.toString(port)};

    server = new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertNotNull(server);
    assertThat(server).isInstanceOf(SeleniumServer.class);
    waitUntilServerIsAvailableOnPort(port);

    String content = getContentOf(port, "/");
    assertThat(content).contains("Whoops! The URL specified routes to this help page.");

    String status = getContentOf(port, "/wd/hub/status");
    Map<?, ?> statusMap = new Json().toType(status, Map.class);
    assertThat(statusMap.get("status")).isEqualTo(0L);
  }

  @Test
  public void launchesStandaloneByDefault() throws Exception {
    int port = PortProber.findFreePort();
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-port", Integer.toString(port)};

    server = new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertNotNull(server);
    assertThat(server).isInstanceOf(SeleniumServer.class);
    waitUntilServerIsAvailableOnPort(port);
  }

  @Test
  public void canGetDebugLogFromStandalone() throws Exception {
    int port = PortProber.findFreePort();
    Path tempLog = Files.createTempFile("test", ".log");
    String[] args = {"-debug", "-log", tempLog.toString(), "-port", Integer.toString(port)};

    server = new GridLauncherV3().launch(args);
    assertNotNull(server);

    WebDriver driver = new RemoteWebDriver(new URL(String.format("http://localhost:%d/wd/hub", port)),
                                           GridTestHelper.getDefaultBrowserCapability());
    driver.quit();
    assertThat(readAll(tempLog)).contains("DEBUG [WebDriverServlet.handle]");
  }

  @Test(timeout = 20000L)
  public void canSetSessionTimeoutForStandalone() throws Exception {
    int port = PortProber.findFreePort();
    Path tempLog = Files.createTempFile("test", ".log");
    String[] args = {"-log", tempLog.toString(), "-port", Integer.toString(port), "-timeout", "5"};

    server = new GridLauncherV3().launch(args);
    assertNotNull(server);

    WebDriver driver = new RemoteWebDriver(new URL(String.format("http://localhost:%d/wd/hub", port)),
                                           GridTestHelper.getDefaultBrowserCapability());
    long start = System.currentTimeMillis();
    new FluentWait<>(tempLog).withTimeout(Duration.ofSeconds(100))
        .until(file -> readAll(file).contains("Removing session"));
    long end = System.currentTimeMillis();
    assertThat(end - start).isBetween(5000L, 15000L);
  }

  private String readAll(Path file) {
    try {
      return String.join("", Files.readAllLines(file));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void cannotStartHtmlSuite() {
    ByteArrayOutputStream outSpy = new ByteArrayOutputStream();
    String[] args = {"-htmlSuite", "*quantum", "http://base.url", "suite.html", "report.html"};

    new GridLauncherV3(new PrintStream(outSpy)).launch(args);
    assertThat(outSpy.toString()).contains("Download the Selenium HTML Runner");
  }

  @Test
  public void testRegisterNodeToHub() throws Exception {
    int hubPort = PortProber.findFreePort();
    String[] hubArgs = {"-role", "hub", "-host", "localhost", "-port", Integer.toString(hubPort)};

    server = new GridLauncherV3().launch(hubArgs);
    waitUntilServerIsAvailableOnPort(hubPort);

    int nodePort = PortProber.findFreePort();
    String[] nodeArgs = {"-role", "node", "-host", "localhost", "-hub", "http://localhost:" + hubPort,
                         "-browser", "browserName=htmlunit,maxInstances=1", "-port", Integer.toString(nodePort)};
    node = new GridLauncherV3().launch(nodeArgs);
    waitUntilServerIsAvailableOnPort(nodePort);

    waitForTextOnHubConsole(hubPort, "htmlunit");
    assertThat(countTextFragmentsOnConsole(hubPort, "htmlunit.png")).isEqualTo(1);
  }

  /*
    throwOnCapabilityNotPresent is a flag used in the ProxySet. It is configured in the hub,
    and then passed to the registry, finally to the ProxySet.
    This test checks that the flag value makes it all the way to the ProxySet. Default is "true".
   */
  @Test
  public void testThrowOnCapabilityNotPresentFlagIsUsed() {
    int hubPort = PortProber.findFreePort();
    String[] hubArgs = {"-role", "hub", "-host", "localhost", "-port", Integer.toString(hubPort),
                        "-throwOnCapabilityNotPresent", "true"};

    server = new GridLauncherV3().launch(hubArgs);
    Hub hub = (Hub) server;
    assertNotNull("Hub didn't start with given parameters." ,hub);

    assertTrue("throwOnCapabilityNotPresent was false in the Hub and it was passed as true",
                hub.getConfiguration().throwOnCapabilityNotPresent);
    assertTrue("throwOnCapabilityNotPresent was false in the ProxySet and it was passed as true",
                hub.getRegistry().getAllProxies().isThrowOnCapabilityNotPresent());

    // Stopping the hub and starting it with a new throwOnCapabilityNotPresent value
    hub.stop();
    hubArgs = new String[]{"-role", "hub", "-host", "localhost", "-port", Integer.toString(hubPort),
                           "-throwOnCapabilityNotPresent", "false"};
    server = new GridLauncherV3().launch(hubArgs);
    hub = (Hub) server;
    assertNotNull("Hub didn't start with given parameters." ,hub);

    assertFalse("throwOnCapabilityNotPresent was true in the Hub and it was passed as false",
                hub.getConfiguration().throwOnCapabilityNotPresent);
    assertFalse("throwOnCapabilityNotPresent was true in the ProxySet and it was passed as false",
                hub.getRegistry().getAllProxies().isThrowOnCapabilityNotPresent());
  }

  @Test
  public void canStartHubUsingConfigFile() throws Exception {
    int hubPort = PortProber.findFreePort();
    Path hubConfig = Files.createTempFile("hub", ".json");
    String hubJson = String.format(
        "{ \"port\": %s,\n"
        + " \"newSessionWaitTimeout\": -1,\n"
        + " \"servlets\" : [],\n"
        + " \"withoutServlets\": [],\n"
        + " \"custom\": {},\n"
        + " \"prioritizer\": null,\n"
        + " \"capabilityMatcher\": \"org.openqa.grid.internal.utils.DefaultCapabilityMatcher\",\n"
        + " \"registry\": \"org.openqa.grid.internal.DefaultGridRegistry\",\n"
        + " \"throwOnCapabilityNotPresent\": true,\n"
        + " \"cleanUpCycle\": 10000,\n"
        + " \"role\": \"hub\",\n"
        + " \"debug\": false,\n"
        + " \"browserTimeout\": 30000,\n"
        + " \"timeout\": 3600\n"
        + "}", hubPort);
    Files.write(hubConfig, hubJson.getBytes());
    String[] hubArgs = {"-role", "hub",  "-host", "localhost", "-hubConfig", hubConfig.toString()};
    server = new GridLauncherV3().launch(hubArgs);
    waitUntilServerIsAvailableOnPort(hubPort);

    assertThat(server).isInstanceOf(Hub.class);
    GridHubConfiguration realHubConfig = ((Hub) server).getConfiguration();
    assertEquals(10000, realHubConfig.cleanUpCycle.intValue());
    assertEquals(30000, realHubConfig.browserTimeout.intValue());
    assertEquals(3600, realHubConfig.timeout.intValue());

    int nodePort = PortProber.findFreePort();
    String[] nodeArgs = {"-role", "node", "-host", "localhost", "-hub", "http://localhost:" + hubPort,
                         "-browser", "browserName=htmlunit,maxInstances=1", "-port", Integer.toString(nodePort)};
    node = new GridLauncherV3().launch(nodeArgs);
    waitUntilServerIsAvailableOnPort(nodePort);

    waitForTextOnHubConsole(hubPort, "htmlunit");
    assertThat(countTextFragmentsOnConsole(hubPort, "htmlunit.png")).isEqualTo(1);
  }

  @Test
  public void canStartNodeUsingConfigFile() throws Exception {
    int hubPort = PortProber.findFreePort();
    String[] hubArgs = {"-role", "hub", "-port", Integer.toString(hubPort)};
    server = new GridLauncherV3().launch(hubArgs);
    waitUntilServerIsAvailableOnPort(hubPort);

    int nodePort = PortProber.findFreePort();
    Path nodeConfig = Files.createTempFile("node", ".json");
    String nodeJson = String.format(
        "{\n"
        + " \"capabilities\": [ { \"browserName\": \"htmlunit\", \"maxInstances\": 1 } ],\n"
        + " \"proxy\": \"org.openqa.grid.selenium.proxy.DefaultRemoteProxy\",\n"
        + " \"maxSession\": 10,\n"
        + " \"port\": %s,\n"
        + " \"register\": true,\n"
        + " \"registerCycle\": 10000,\n"
        + " \"hub\": \"http://localhost:%s\",\n"
        + " \"nodeStatusCheckTimeout\": 10000,\n"
        + " \"nodePolling\": 10000,\n"
        + " \"role\": \"node\",\n"
        + " \"unregisterIfStillDownAfter\": 20000,\n"
        + " \"downPollingLimit\": 2,\n"
        + " \"debug\": false,\n"
        + " \"servlets\" : [],\n"
        + " \"withoutServlets\": [],\n"
        + " \"custom\": {}\n"
        + "}", nodePort, hubPort);
    Files.write(nodeConfig, nodeJson.getBytes());
    String[] nodeArgs = {"-role", "node", "-nodeConfig", nodeConfig.toString() };
    node = new GridLauncherV3().launch(nodeArgs);
    waitUntilServerIsAvailableOnPort(nodePort);

    waitForTextOnHubConsole(hubPort, "htmlunit");
    assertThat(countTextFragmentsOnConsole(hubPort, "htmlunit.png")).isEqualTo(1);
  }

  private void waitForTextOnHubConsole(Integer hubPort, String text) throws MalformedURLException {
    new FluentWait<>(new URL(String.format("http://localhost:%d/grid/console", hubPort)))
        .withTimeout(Duration.ofSeconds(5)).pollingEvery(Duration.ofMillis(50))
        .until((Function<URL, Boolean>) u -> {
          try (InputStream is = u.openConnection().getInputStream();
               InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8);
               BufferedReader reader = new BufferedReader(isr)) {
            return reader.lines().anyMatch(l -> l.contains(text));
          } catch (IOException ioe) {
            return false;
          }
        });
  }

  private void waitUntilServerIsAvailableOnPort(int port) throws Exception {
    waitUntilAvailable(String.format("http://localhost:%d/wd/hub/status", port));
  }

  private void waitUntilAvailable(String url) throws Exception {
    new UrlChecker().waitUntilAvailable(10, TimeUnit.SECONDS, new URL(url));
  }

  private String getContentOf(int port, String path) throws Exception {
    String baseUrl = String.format("http://localhost:%d", port);
    HttpClient client = HttpClient.Factory.createDefault().createClient(new URL(baseUrl));
    HttpRequest req = new HttpRequest(HttpMethod.GET, path);
    return string(client.execute(req));

  }

  private int countTextFragmentsOnConsole(int hubPort, String target) throws Exception {
    String gridConsole = getContentOf(hubPort, "/grid/console");
    return countSubstring(gridConsole, target);
  }

  private int countSubstring(String s, String target) {
    int lastIndex = 0;
    int count = 0;

    while(lastIndex != -1){
      lastIndex = s.indexOf(target, lastIndex);
      if(lastIndex != -1){
        count++;
        lastIndex += target.length();
      }
    }

    return count;
  }
}
