/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.ipc.stats;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.exception.ResourceNotFoundException;

import org.apache.avro.Protocol.Message;
import org.apache.avro.ipc.RPCContext;

/**
 * Exposes information provided by a StatsPlugin as
 * a web page.
 *
 * This class follows the same synchronization conventions
 * as StatsPlugin, to avoid requiring StatsPlugin to serve
 * a copy of the data.
 */ 
public class StatsServlet extends HttpServlet {
  private final StatsPlugin statsPlugin;
  private VelocityEngine ve;
  
  public StatsServlet(StatsPlugin statsPlugin) {
    this.statsPlugin = statsPlugin;
    this.ve = new VelocityEngine();
    ve.addProperty("resource.loader", "file");
    ve.addProperty("file.resource.loader.path",
    		getClass().getProtectionDomain().getCodeSource().
    		getLocation().getPath());
  }

  public static List<String> escapeStringArray(List<String> input) {
    for (int i = 0; i < input.size(); i++) {
      input.set(i, "\"" + input.get(i) + "\"");
    }
    return input;
  }
  
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
  	if (req.getRequestURI().endsWith(".js") || 
  	    req.getRequestURI().endsWith(".css")) {
  		Template t = null;
  		String[] queryParts = req.getRequestURI().split("/");
  		String jsFileName = queryParts[queryParts.length - 1];
      resp.setContentType("text/javascript");
			try {
			  InputStream is = getClass().getClassLoader().getResourceAsStream(
			      "org/apache/avro/ipc/stats/templates/" + jsFileName);
				PrintWriter out = resp.getWriter();
				int i;
				while ((i = is.read()) != -1) {
				  out.write(i);
				}
				return;
				
			} catch (Exception e) {
				e.printStackTrace();
			}
      t.merge(new VelocityContext(), resp.getWriter());
  	}
  	else {
	    resp.setContentType("text/html");
	    try {
				writeStats(resp.getWriter());
			} catch (Exception e) {
				e.printStackTrace();
			}
  	}
  }

  void writeStats(Writer w) throws IOException {
    VelocityContext context = new VelocityContext();
    context.put("title", "PAGE TITLE"); 
    
    ArrayList<String> rpcs = new ArrayList<String>();
    ArrayList<String> methods = new ArrayList<String>();
    for (Entry<RPCContext, Stopwatch> rpc : 
    	   this.statsPlugin.activeRpcs.entrySet()) {
      rpcs.add(renderActiveRpc(rpc.getKey(), rpc.getValue()));
    }
    
    // Get set of all seen messages
    Set<Message> keys = null;
    synchronized(this.statsPlugin.methodTimings) {
    	 keys = this.statsPlugin.methodTimings.keySet();
    }
    
    for (Message m: keys) {
    	methods.add(renderMethod(m));
    }
    
    context.put("methodDetails", methods);
    context.put("inFlightRpcs", rpcs);
    
    SimpleDateFormat formatter = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss");
    context.put("currTime", formatter.format(new Date()));
    context.put("startupTime", formatter.format(statsPlugin.startupTime));
    
    Template t;
    try {
      t = ve.getTemplate("org/apache/avro/ipc/stats/templates/statsview.vm");
    } catch (ResourceNotFoundException e) {
      throw new IOException();
    } catch (ParseErrorException e) {
      throw new IOException();
    } catch (Exception e) {
      throw new IOException();
    }
    t.merge(context, w);
   
  }

  private String renderActiveRpc(RPCContext rpc, Stopwatch stopwatch) 
      throws IOException {
  	String out = new String();
    out += rpc.getMessage().getName() + ": " + 
        formatMillis(StatsPlugin.nanosToMillis(stopwatch.elapsedNanos()));
    return out;
  }

  private String renderMethod(Message message) 
      throws IOException {
  	String out = new String();
    out += "<h4>" + message.getName() + "</h4>";
  	out += "<table width='100%'><tr>";
  	synchronized(this.statsPlugin.methodTimings) {
  	  out += "<td>";
      FloatHistogram<?> hist = this.statsPlugin.methodTimings.get(message);
      out += "<p>Number of calls: " + Integer.toString(hist.getCount()) + "<br>";
      out += "Average Duration: " + hist.getMean() + "<br>";
      out += "Std Dev: " + hist.getUnbiasedStdDev() + "</p>";
      out += "\n<script>\n";
      out += "makeBarChart(" + 
        Arrays.toString(hist.getSegmenter().getBoundaryLabels().toArray()) 
        + ", " + Arrays.toString(escapeStringArray(hist.getSegmenter().
        getBucketLabels()).toArray()) 
        + ", " + Arrays.toString(hist.getHistogram()) + ")\n";
      
      out += "</script>\n";
      out += "</td>";
  	}
   	
    synchronized(this.statsPlugin.receivePayloads) {
      out += "<td>";
      IntegerHistogram<?> hist = this.statsPlugin.receivePayloads.get(message);
      out += "<p>Number of receives: " + Integer.toString(hist.getCount()) + "<br>";
      out += "Average Payload: " + hist.getMean() + "<br>";
      out += "Std Dev: " + hist.getUnbiasedStdDev() + "</p>";
      out += "\n<script>\n";
      out += "makeBarChart(" + 
        Arrays.toString(hist.getSegmenter().getBoundaryLabels().toArray()) 
        + ", " + Arrays.toString(escapeStringArray(hist.getSegmenter().
        getBucketLabels()).toArray()) 
        + ", " + Arrays.toString(hist.getHistogram()) + ")\n";
      out += "</script>\n";
      out += "</td>";
  	}
   	
    synchronized(this.statsPlugin.sendPayloads) {
      out += "<td>";
      IntegerHistogram<?> hist = this.statsPlugin.sendPayloads.get(message);
      out += "<p>Number of sends: " + Integer.toString(hist.getCount()) + "<br>";
      out += "Average Payload: " + hist.getMean() + "<br> ";
      out += "Std Dev: " + hist.getUnbiasedStdDev() + "</p>";
      out += "\n<script>\n";
      out += "makeBarChart(" + 
        Arrays.toString(hist.getSegmenter().getBoundaryLabels().toArray()) 
        + ", " + Arrays.toString(escapeStringArray(hist.getSegmenter().
        getBucketLabels()).toArray()) 
        + ", " + Arrays.toString(hist.getHistogram()) + ")\n";
      out += "</script>\n";
      out += "</td>";
    }
    out += "</tr></table>";
    
    return out;
  }

  private CharSequence formatMillis(float millis) {
    return String.format("%.0fms", millis);
  }
}
