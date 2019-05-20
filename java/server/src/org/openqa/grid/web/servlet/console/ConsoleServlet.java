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

package org.openqa.grid.web.servlet.console;

import com.google.common.io.ByteStreams;

import org.openqa.grid.internal.GridRegistry;
import org.openqa.grid.internal.RemoteProxy;
import org.openqa.grid.internal.utils.HtmlRenderer;
import org.openqa.grid.internal.utils.configuration.GridHubConfiguration;
import org.openqa.grid.web.servlet.RegistryBasedServlet;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.remote.DesiredCapabilities;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class ConsoleServlet extends RegistryBasedServlet {

  private static final long serialVersionUID = 8484071790930378855L;
  private static String coreVersion;

  public static final String CONSOLE_PATH_PARAMETER = "webdriver.server.consoleservlet.path";

  public ConsoleServlet() {
    this(null);
  }

  public ConsoleServlet(GridRegistry registry) {
    super(registry);
    coreVersion = new BuildInfo().getReleaseLabel();
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    process(request, response);
  }

  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    process(request, response);
  }

  protected void process(HttpServletRequest request, HttpServletResponse response)
      throws IOException {

    int refresh = -1;

    if (request.getParameter("refresh") != null) {
      try {
        refresh = Integer.parseInt(request.getParameter("refresh"));
      } catch (NumberFormatException e) {
        // ignore wrong param
      }

    }

    response.setContentType("text/html");
    response.setCharacterEncoding("UTF-8");
    response.setStatus(200);

    StringBuilder builder = new StringBuilder();

    builder.append("<html>");
    builder.append("<head>");
    builder
        .append("<script src='/grid/resources/org/openqa/grid/images/jquery-3.1.1.min.js'></script>");

    builder.append("<script src='/grid/resources/org/openqa/grid/images/consoleservlet.js'></script>");

    builder
        .append("<link href='/grid/resources/org/openqa/grid/images/consoleservlet.css' rel='stylesheet' type='text/css' />");
    builder
        .append("<link href='/grid/resources/org/openqa/grid/images/favicon.ico' rel='icon' type='image/x-icon' />");


    if (refresh != -1) {
      builder.append(String.format("<meta http-equiv='refresh' content='%d' />", refresh));
    }
    builder.append("<title>Grid Console</title>");

    builder.append("<style>");
    builder.append(".busy {");
    builder.append(" opacity : 0.4;");
    builder.append("filter: alpha(opacity=40);");
    builder.append("}");
    builder.append("</style>");
    builder.append("</head>");

    builder.append("<body>");

    builder.append("<div id='main-content'>");

    builder.append(getHeader());

    // TODO freynaud : registry to return a copy of proxies ?
    List<String> nodes = new ArrayList<>();
    for (RemoteProxy proxy : getRegistry().getAllProxies()) {
      HtmlRenderer beta = proxy.getHtmlRender();
      nodes.add(beta.renderSummary());
    }

    int size = nodes.size();
    int rightColumnSize = size / 2;
    int leftColumnSize = size - rightColumnSize;



    builder.append("<div id='left-column'>");
    for (int i = 0; i < leftColumnSize; i++) {
      builder.append(nodes.get(i));
    }


    builder.append("</div>");

    builder.append("<div id='right-column'>");
    for (int i = leftColumnSize; i < nodes.size(); i++) {
      builder.append(nodes.get(i));
    }


    builder.append("</div>");

    builder.append("<div class='clearfix'></div>");

    builder.append(getRequestQueue());

    builder.append(getConfigInfo());

    builder.append("</div>");
    builder.append("</body>");
    builder.append("</html>");

    try (InputStream in = new ByteArrayInputStream(builder.toString().getBytes("UTF-8"))) {
      ByteStreams.copy(in, response.getOutputStream());
    } finally {
      response.getOutputStream().close();
    }
  }

  private Object getRequestQueue() {
    StringBuilder builder = new StringBuilder();
    builder.append("<div>");
    int numUnprocessedRequests = getRegistry().getNewSessionRequestCount();

    if (numUnprocessedRequests > 0) {
      builder.append(String.format("%d requests waiting for a slot to be free.",
          numUnprocessedRequests));
    }

    builder.append("<ul>");
    for (DesiredCapabilities req : getRegistry().getDesiredCapabilities()) {
      builder.append("<li>").append(req).append("</li>");
    }
    builder.append("</ul>");
    builder.append("</div>");
    return builder.toString();
  }

  private Object getHeader() {
    StringBuilder builder = new StringBuilder();
    builder.append("<div id='header'>");
    builder.append("<h1><a href='/grid/console'>Selenium</a></h1>");
    builder.append("<h2>Grid Console v.");
    builder.append(coreVersion);
    builder.append("</h2>");
    builder.append("<div><a id='helplink' target='_blank' href='https://github.com/SeleniumHQ/selenium/wiki/Grid2'>Help</a></div>");
    builder.append("</div>");
    return builder.toString();
  }


  /**
   * retracing how the hub config was built to help debugging.
   *
   * @return html representation of the hub config
   */
  private String getConfigInfo() {
    StringBuilder builder = new StringBuilder();

    builder.append("<div id='hub-config-container'>");
    GridHubConfiguration config = getRegistry().getHub().getConfiguration();
    builder.append("<div id='hub-config-content'>");
    builder.append("<b>Config for the hub :</b><br/>");
    builder.append(prettyHtmlPrint(config));
    builder.append(getVerboseConfig()); // Display verbose configuration details
    builder.append("</div>"); // End of Config Content

    // Display View/Hide Link at the bottom beneath the details
    builder.append("<a id='config-view-toggle' href='#'>View Config</a>");
    builder.append("</div>"); // End of Config Container
    return builder.toString();
  }

  /**
   * Displays more detailed configuration
   * @return html representation of the verbose hub config
   */
  private String getVerboseConfig() {
    StringBuilder builder = new StringBuilder();
    GridHubConfiguration config = getRegistry().getHub().getConfiguration();

    builder.append("<div id='verbose-config-container'>");
    builder.append("<a id='verbose-config-view-toggle' href='#'>View Verbose</a>");

    builder.append("<div id='verbose-config-content'>");
    GridHubConfiguration tmp = new GridHubConfiguration();

    builder.append("<br/><b>The final configuration comes from:</b><br/>");
    builder.append("<b>the default :</b><br/>");
    builder.append(prettyHtmlPrint(tmp));

    if (config.getRawArgs() != null) {
      builder.append("<b>updated with command line options:</b><br/>");
      builder.append(String.join(" ", config.getRawArgs()));

      if (config.getConfigFile() != null) {
        builder.append("<br/><b>and configuration loaded from ").append(config.getConfigFile()).append(":</b><br/>");
        try {
          builder.append(String.join("<br/>", Files.readAllLines(new File(config.getConfigFile()).toPath())));
        } catch (IOException e) {
          builder.append("<b>").append(e.getMessage()).append("</b>");
        }
      }
    }
    builder.append("</div>"); // End of Verbose Content
    builder.append("</div>"); // End of Verbose Container
    return builder.toString();
  }

  private String prettyHtmlPrint(GridHubConfiguration config) {
    return config.toString("<abbr title='%1$s'>%1$s : </abbr>%2$s</br>");
  }
}
