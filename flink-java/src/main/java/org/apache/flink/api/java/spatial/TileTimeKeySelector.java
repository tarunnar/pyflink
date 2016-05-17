/*
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
package org.apache.flink.api.java.spatial;

import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple3;

/**
 * Extracts the aquisition time for a {@link Tuple3<>} that contains an envi tile and returns it as
 * timestamp.
 * 
 * @author Mathias Peters <mathias.peters@informatik.hu-berlin.de>
 *
 * @param <Key>
 */
public class TileTimeKeySelector<Key> implements KeySelector<Tuple3<String, byte[], byte[]>, Long> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	@Override
	public Long getKey(Tuple3<String, byte[], byte[]> value) throws Exception {
		TileInfoWrapper wrapper = new TileInfoWrapper(value.f1);
		return wrapper.getAcquisitionDateAsLong();
	}

}
