/*
 * Copyright (c) 2012, Tobias Markmann <tm@ayena.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import com.android.internal.os.IFlowGraph;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Pair;

import com.android.server.flowgraph.CommunicationLink;

public class FlowGraphService extends IFlowGraph.Stub {
	private static final String TAG = "FlowGraph";
	
	private static IFlowGraph flow_graph_reference = null;
	
	// system graph state
	
	// communication links <UID, UID> -->  (TaintTag --> Communication Link)
	private static Map<Pair<Integer, Integer>, Map<Integer, CommunicationLink>> communication_links = new HashMap<Pair<Integer,Integer>, Map<Integer, CommunicationLink>>(); 
	
	// UID --> <PIDs>
	private static Map<Integer, Set<Integer>> running_processes = new HashMap<Integer, Set<Integer>>();

	// PID --> package name
	private static Map<Integer, String> process_names = new HashMap<Integer, String>();
	
	// Timer to dynamic flow updates and maintenance work
	private static Timer timer = new Timer();
	
	// graph management methods
	private static void removeEdgesForUID(int uid) {
		List<Pair<Integer, Integer>> removeEdges = new ArrayList<Pair<Integer, Integer>>();
		
		for (Map.Entry<Pair<Integer, Integer>, Map<Integer, CommunicationLink>> entry : communication_links.entrySet()) {
			if (entry.getKey().first == uid || entry.getKey().second == uid) {
				removeEdges.add(entry.getKey());
			}
		}
		
		for (Pair<Integer, Integer> entry : removeEdges) {
			communication_links.remove(entry);
		}
	}
	
	private static void addCommunicationTraffic(int from_uid, int to_uid, int tag, int bytes) {
		assert(running_processes.containsKey(from_uid));
		assert(running_processes.containsKey(to_uid));
		
		Map<Integer, CommunicationLink> links = null;
		Pair<Integer, Integer> link_pair = Pair.create(from_uid, to_uid);
		links = communication_links.get(link_pair);
		if (links != null) {
			CommunicationLink comm_link = links.get(tag);
			if (comm_link != null) {
				comm_link.addBytes(bytes);
			} else {
				links.put(tag, new CommunicationLink(tag, bytes));
			}
		} else {
			links = new HashMap<Integer, CommunicationLink>();
			links.put(tag, new CommunicationLink(tag, bytes));
			communication_links.put(link_pair, links);
		}
	}
	
	// maintanence and dynamic update
	private static void updateFlowGraph() {		
		for (Map.Entry<Pair<Integer, Integer>, Map<Integer, CommunicationLink>> entry : communication_links.entrySet()) {
			List<Integer> links_to_remove = new ArrayList<Integer>();
			
			for (Map.Entry<Integer, CommunicationLink> link : entry.getValue().entrySet()) {
				CommunicationLink comm_link = link.getValue();
				comm_link.shift();
				if (comm_link.getBytes() == 0) {
					links_to_remove.add(link.getKey());
					Log.w(TAG, "Removing link: from_uid = " + entry.getKey().first + ", to_uid = " + entry.getKey().second + ", tag = " + link.getKey());
				} else {
					Log.w(TAG, "Current link throughput: from_uid = " + entry.getKey().first + ", to_uid = " + entry.getKey().second + ", tag = " + link.getKey() + ", bytes per min = " + comm_link.getBytes());
				}
			}
			
			for (Integer tag : links_to_remove) {
				entry.getValue().remove(tag);
			}
		}
	}
	
	public FlowGraphService() {
		timer.scheduleAtFixedRate(new FlowGraphUpdateTask(), 0, 10000);
	}
	
	private class FlowGraphUpdateTask extends TimerTask { 
        public void run() {
        	updateFlowGraph();
        }
    }    
	
	public static void preCommunicationHelper(int from_pid, int from_uid, int to_pid, int to_uid, int size_in_bytes, int taint_tag) {
	    if (flow_graph_reference == null) {
	        flow_graph_reference = (IFlowGraph) ServiceManager.getService("flowgraph");
	    }
	    try {
            flow_graph_reference.preCommunication(from_pid, from_uid, to_pid, to_uid, size_in_bytes, taint_tag);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
	}
	/*
	private void deleteInfoNodes(String node_id) {
	    final ArrayList<String> nodesToDelete = getInfoNodes(node_id);
	    
	    if (!nodesToDelete.isEmpty()) {
	        StringBuilder delete_nodes_json = new StringBuilder();
	        delete_nodes_json.append("{\"dn\":{\"");
            delete_nodes_json.append(nodesToDelete.get(0));
            delete_nodes_json.append("\":{}}}");
	        
	        for (int n = 1; n < nodesToDelete.size(); n++) {
	            delete_nodes_json.append("\r{\"dn\":{\"");
	            delete_nodes_json.append(nodesToDelete.get(n));
	            delete_nodes_json.append("\":{}}}");
	        }
	        sendGraphUpdate(delete_nodes_json.toString());
	    }
	}*/

	@Override
	public void spawnProcess(final int pid, int uid) {
		Log.w(TAG, "Spawn Process (" + pid + ")");
		
		if (!running_processes.containsKey(uid)) {
			running_processes.put(uid, new HashSet<Integer>(){{ add(pid); }});
		} else {
			running_processes.get(uid).add(pid);
		}
		/*
		try {
			String json = String.format("{\"an\":{\"pid_%d\":{\"label\":\"Process %d\"}}}", pid, pid);
			sendGraphUpdate(json);
		} catch (Exception e) {

		}*/
	}

	@Override
	public void exitProcess(int pid, int uid) {
		Log.w(TAG, "Exit Process (" + pid + ")");
		
		assert(running_processes.containsKey(uid));
		running_processes.get(uid).remove(pid);
		process_names.remove(pid);
		if (running_processes.get(uid).isEmpty()) {
			running_processes.remove(uid);
			removeEdgesForUID(uid);
		}
		
		/*String json = String.format("{\"dn\":{\"pid_%d\":{}}}", pid);
		sendGraphUpdate(json);
		deleteInfoNodes(String.format("pid_%d", pid));*/
	}

	@Override
	public void setProcessName(int pid, String name) {
		Log.w(TAG, "Process Name (" + pid + "): " + name);
		process_names.put(pid, name);
		try {
			String json = String.format("{\"an\":{\"pid_%d_processInfo\":{\"label\":\"%s\"}}}", pid, name) + "\r" +
				String.format("{\"ae\":{\"pid_%d_processInfo_edge\":{\"source\":\"pid_%d\",\"target\":\"pid_%d_processInfo\",\"directed\":false}}}", pid, pid, pid);
			sendGraphUpdate(json);
			//addInfoNode(String.format("pid_%d", pid), String.format("pid_%d_processInfo", pid));
		} catch (Exception e) {

		}
	}
	
	@Override
	public void preCommunication(int from_pid, int from_uid, int to_pid, int to_uid, int size_in_bytes, int taint_tag) {
	    Log.w(TAG, "Communication from " +from_pid+" (UID="+from_uid+") to " +to_pid+" (UID="+to_uid+") of "+size_in_bytes+" bytes tagged as "+taint_tag+".\n");
	    
	    if (taint_tag != 0) {
	    	for (int i = 0; i < 17; i++) {
	    		if (((taint_tag >> i) & 1) == 1) {
	    			addCommunicationTraffic(from_uid, to_uid, (int) Math.pow(2, i), size_in_bytes);
	    		}
	    	}
	    }
	    
	    /*try {
            String json = String.format("{\"ae\":{\"communication_%d\":{\"source\":\"pid_%d\",\"target\":\"pid_%d\",\"directed\":true, \"label\":  \"Size: %d\"}}}", edge_id, from_pid, to_pid, size_in_bytes);
            sendGraphUpdate(json);
            edge_id++;
        } catch (Exception e) {

        }*/
	}
	
	@Override
	public String logGraphState() {
		return "";
	}
	

	static private void sendGraphUpdate(String data) {
		/*
		Log.i(TAG, "AppGraph.sendGraphUpdate: data = " + data);
		try {
            final URL graphURL = new URL("http://10.0.2.2:8080/workspace0?operation=updateGraph");

            HttpURLConnection urlConnection = (HttpURLConnection) graphURL.openConnection();
            try {
                urlConnection.setDoOutput(true);
                urlConnection.setChunkedStreamingMode(0);

                OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
               
                out.write(data.getBytes("UTF-8"));
                out.flush();

                InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                //readStream(in);
            } finally {
                urlConnection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Exception on AppGraph.sendGraphUpdate: " + e.getMessage());
            e.printStackTrace();
        }*/
	}
}