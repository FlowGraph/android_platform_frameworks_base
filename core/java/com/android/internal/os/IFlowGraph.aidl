/**
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
 
package com.android.internal.os;

interface IFlowGraph
{
	void spawnProcess(int pid, int uid);
	void exitProcess(int pid, int uid);
	void setProcessName(int pid, String name);
	
	void preCommunication(int from_pid, int from_uid, int to_pid, int to_uid, int size_in_bytes, int taint_tag);

	// debug methods
	String logGraphState();
}
