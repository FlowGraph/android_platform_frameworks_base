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
import android.os.Process;
import android.util.Log;
import android.util.Pair;

import com.android.server.flowgraph.CommunicationLink;

import dalvik.system.Taint;
import dalvik.system.FlowGraph;

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
	
	synchronized private static void addCommunicationTraffic(int from_uid, int to_uid, int tag, int bytes) {
		assert(running_processes.containsKey(from_uid));
		assert(running_processes.containsKey(to_uid));
		
		Map<Integer, CommunicationLink> links = null;
		Pair<Integer, Integer> link_pair = Pair.create(from_uid, to_uid);
		links = communication_links.get(link_pair);
		if (links == null) {
			links = new HashMap<Integer, CommunicationLink>();
			communication_links.put(link_pair, links);
		}
		
		CommunicationLink comm_link = links.get(tag);
			
		if (comm_link == null) {
			comm_link = new CommunicationLink(tag, bytes);
			links.put(tag, comm_link);
		} else {
			comm_link.addBytes(bytes);
		}
		
		// CONTACT
		if (comm_link.getTag() == 0x00000002) {
			if (comm_link.getBytes() > 1000) {
				Log.w(TAG, "Communication from " + from_uid + " to " + to_uid + " exceeded limit of 1000 bytes/min. Killing receiving user's processes.");
				killProcess(to_uid, 0);
			} else {
				Log.w(TAG, "Communication from " + from_uid + " to " + to_uid + " throughput at " + comm_link.getBytes() + "bytes/min (Taint: " + comm_link.getTag() + ")");
			}
		}
		
		// SMS
		if (comm_link.getTag() == 0x00000200) {
			if (comm_link.getBytes() > 10000) {
				Log.w(TAG, "Communication from " + from_uid + " to " + to_uid + " exceeded limit of 5000 bytes/min. Killing receiving user's processes.");
				killProcess(to_uid, 0);
			} else {
				Log.w(TAG, "Communication from " + from_uid + " to " + to_uid + " throughput at " + comm_link.getBytes() + "bytes/min (Taint: " + comm_link.getTag() + ")");
			}
		}
		
		
		

	}
	
	// maintanence and dynamic update
	synchronized private static void updateFlowGraph() {		
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
	
	private static void killProcess(int uid, int pid) {
		Log.w(TAG, "kill process: " + uid + " (uid)    " + pid + " (pid)");
		if (pid != 0) {
			Log.w(TAG, "\tkilling single process " + pid);
			Process.killProcess(pid);
		} else {
			for (int pid_to_kill : running_processes.get(uid)) {
				Log.w(TAG, "\tkilling process " + pid_to_kill);
				Process.killProcess(pid_to_kill);
			}
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

	@Override
	synchronized public void spawnProcess(final int pid, int uid) {
		Log.w(TAG, "Spawn Process (" + pid + ")");
		
		if (!running_processes.containsKey(uid)) {
			running_processes.put(uid, new HashSet<Integer>(){{ add(pid); }});
		} else {
			running_processes.get(uid).add(pid);
		}
	}

	@Override
	synchronized public void exitProcess(int pid, int uid) {
		Log.w(TAG, "Exit Process (" + pid + ")");
		
		assert(running_processes.containsKey(uid));
		running_processes.get(uid).remove(pid);
		process_names.remove(pid);
		if (running_processes.get(uid).isEmpty()) {
			running_processes.remove(uid);
			removeEdgesForUID(uid);
		}
	}

	@Override
	synchronized public void setProcessName(int pid, String name) {
		Log.w(TAG, "Process Name (" + pid + "): " + name);
		process_names.put(pid, name);
	}
	
	@Override
	synchronized public void preCommunication(int from_pid, int from_uid, int to_pid, int to_uid, int size_in_bytes, int taint_tag) {
	    if (FlowGraph.LOG_COMMUNICATION) Log.w(TAG, "Communication from " +from_pid+" (UID="+from_uid+") to " +to_pid+" (UID="+to_uid+") of "+size_in_bytes+" bytes tagged as "+taint_tag+".\n");
	    
	    if (taint_tag != 0) {
	    	for (int i = 0; i < 17; i++) {
	    		if (((taint_tag >> i) & 1) == 1) {
	    			Log.w(TAG, "addCommunicationTraffic(" + from_uid + ", " + to_uid + ", " + (int) Math.pow(2, i) + ", " + size_in_bytes + ")");
	    			addCommunicationTraffic(from_uid, to_uid, (int) Math.pow(2, i), size_in_bytes);
	    		}
	    	}
	    }
	}
	
	@Override
	public String logGraphState() {
		StringBuilder dotGraph = new StringBuilder(200);
		dotGraph.append("digraph flowgraphdump {\n");
		
		// add UID sandboxes
		for (Integer uid : running_processes.keySet()) {
			String sandboxStr = "sandbox_" + uid;
			dotGraph.append(sandboxStr + " [label=\"UID " + uid + "\"];\n");
        
			// add process names to sandboxes
			for (Integer pid : running_processes.get(uid)) {
				String pidStr = "processname_" + pid;
				dotGraph.append(pidStr + " [shape=box, label=\"" + process_names.get(pid) + "\"];\n");
				dotGraph.append(sandboxStr + " -> " + pidStr + " [dir=none];\n");
			}
		}
		
		dotGraph.append("\n /* communication links */ \n");
		
		// communication links
		for (Map.Entry<Pair<Integer, Integer>, Map<Integer, CommunicationLink>> entry : communication_links.entrySet()) {			
			for (Map.Entry<Integer, CommunicationLink> link : entry.getValue().entrySet()) {
				CommunicationLink comm_link = link.getValue();
				String sandboxFrom = "sandbox_" + entry.getKey().first;
				String sandboxTo = "sandbox_" + entry.getKey().second;
				dotGraph.append(sandboxFrom + " -> " + sandboxTo + "[label=\" Tag: " + Taint.taintTagToString(link.getKey()) + "\\nThroughput: " + comm_link.getBytes() + " Bytes/min"  + "\"];\n");
				
			}
		}
		
		dotGraph.append("}\n");
		return dotGraph.toString();
	}
}