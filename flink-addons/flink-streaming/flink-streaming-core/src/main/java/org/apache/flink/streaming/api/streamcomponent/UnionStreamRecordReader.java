/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.flink.streaming.api.streamcomponent;

import java.io.IOException;

import org.apache.flink.streaming.api.streamrecord.StreamRecord;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.typeutils.runtime.TupleSerializer;
import org.apache.flink.runtime.plugable.DeserializationDelegate;
import org.apache.flink.runtime.io.network.api.AbstractUnionRecordReader;
import org.apache.flink.runtime.io.network.api.MutableRecordReader;
import org.apache.flink.runtime.io.network.api.Reader;

public final class UnionStreamRecordReader<T extends Tuple> extends AbstractUnionRecordReader<StreamRecord<T>>
		implements Reader<StreamRecord<T>> {

	@SuppressWarnings("rawtypes")
	private final Class<? extends StreamRecord> recordType;

	private StreamRecord<T> lookahead;
	private DeserializationDelegate<T> deserializationDelegate;
	private TupleSerializer<T> tupleSerializer;

	@SuppressWarnings("rawtypes")
	public UnionStreamRecordReader(MutableRecordReader<StreamRecord<T>>[] recordReaders, Class<? extends StreamRecord> class1,
			DeserializationDelegate<T> deserializationDelegate,
			TupleSerializer<T> tupleSerializer) {
		super(recordReaders);
		this.recordType = class1;
		this.deserializationDelegate = deserializationDelegate;
		this.tupleSerializer = tupleSerializer;
	}

	@Override
	public boolean hasNext() throws IOException, InterruptedException {
		if (this.lookahead != null) {
			return true;
		} else {
			StreamRecord<T> record = instantiateRecordType();
			record.setDeseralizationDelegate(deserializationDelegate, tupleSerializer);
			if (getNextRecord(record)) {
				this.lookahead = record;
				return true;
			} else {
				return false;
			}
		}
	}

	@Override
	public StreamRecord<T> next() throws IOException, InterruptedException {
		if (hasNext()) {
			StreamRecord<T> tmp = this.lookahead;
			this.lookahead = null;
			return tmp;
		} else {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private StreamRecord<T> instantiateRecordType() {
		try {
			return this.recordType.newInstance();
		} catch (InstantiationException e) {
			throw new RuntimeException("Cannot instantiate class '" + this.recordType.getName()
					+ "'.", e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Cannot instantiate class '" + this.recordType.getName()
					+ "'.", e);
		}
	}
}
