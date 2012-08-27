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

package com.android.server.flowgraph;

public class CommunicationLink {
	static private final int buckets = 6;
	
	private int tag;
	private int bucket[] = new int[buckets];
	
	
	public CommunicationLink(int tag, int bytes) {
		this.tag = tag;
		bucket[(buckets - 1)] += bytes;
	}
	
	public int getTag() {
		return tag;
	}
	
	public void addBytes(int bytes) {
		bucket[(buckets - 1)] +=bytes;
	}
	
	public int getBytes() {
		int sum = 0;
		for (int n = 0; n < buckets; n++) {
			sum += bucket[n];
		}
		return sum;
	}
	
	public void shift() {
		for (int n = 1; n < buckets; n++) {
			bucket[n-1] = bucket[n];
		}
		bucket[buckets-1] = 0;
	}
}