/**
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
 */

package org.apache.flink.streaming.api.invokable.operator;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.function.co.CoReduceFunction;
import org.apache.flink.streaming.api.invokable.operator.co.CoGroupedWindowReduceInvokable;
import org.apache.flink.streaming.api.invokable.util.TimeStamp;
import org.apache.flink.streaming.util.MockCoInvokable;
import org.junit.Test;

public class CoGroupedWindowReduceTest {

	private static class MyCoReduceFunction implements
			CoReduceFunction<Tuple2<String, Integer>, Tuple2<String, Integer>, String> {
		private static final long serialVersionUID = 1L;

		@Override
		public Tuple2<String, Integer> reduce1(Tuple2<String, Integer> value1,
				Tuple2<String, Integer> value2) {
			return new Tuple2<String, Integer>("a", value1.f1 + value2.f1);
		}

		@Override
		public Tuple2<String, Integer> reduce2(Tuple2<String, Integer> value1,
				Tuple2<String, Integer> value2) {
			return new Tuple2<String, Integer>("a", value1.f1 + value2.f1);
		}

		@Override
		public String map1(Tuple2<String, Integer> value) {
			return value.f1.toString();
		}

		@Override
		public String map2(Tuple2<String, Integer> value) {
			return value.f1.toString();
		}
	}

	public static final class MyTimeStamp<T> implements TimeStamp<T> {
		private static final long serialVersionUID = 1L;

		private Iterator<Long> timestamps;
		private long start;

		public MyTimeStamp(List<Long> timestamps) {
			this.timestamps = timestamps.iterator();
			this.start = timestamps.get(0);
		}

		@Override
		public long getTimestamp(T value) {
			long ts = timestamps.next();
			return ts;
		}

		@Override
		public long getStartTime() {
			return start;
		}
	}

	List<Long> timestamps = Arrays.asList(0L, 1L, 1L, 2L, 2L, 8L, 8L, 10L);

	@Test
	public void coGroupedWindowReduceTest() {

		List<Tuple2<String, Integer>> inputs1 = new ArrayList<Tuple2<String, Integer>>();
		inputs1.add(new Tuple2<String, Integer>("a", 1));
		inputs1.add(new Tuple2<String, Integer>("a", 2));
		inputs1.add(new Tuple2<String, Integer>("b", 2));
		inputs1.add(new Tuple2<String, Integer>("b", 2));
		inputs1.add(new Tuple2<String, Integer>("b", 5));
		inputs1.add(new Tuple2<String, Integer>("a", 7));
		inputs1.add(new Tuple2<String, Integer>("b", 9));
		inputs1.add(new Tuple2<String, Integer>("b", 10));

		List<Tuple2<String, Integer>> inputs2 = new ArrayList<Tuple2<String, Integer>>();
		inputs2.add(new Tuple2<String, Integer>("a", 1));
		inputs2.add(new Tuple2<String, Integer>("a", 2));
		inputs2.add(new Tuple2<String, Integer>("b", 2));
		inputs2.add(new Tuple2<String, Integer>("b", 2));
		inputs2.add(new Tuple2<String, Integer>("b", 5));
		inputs2.add(new Tuple2<String, Integer>("a", 7));
		inputs2.add(new Tuple2<String, Integer>("b", 9));
		inputs2.add(new Tuple2<String, Integer>("b", 10));

		List<String> expected = new ArrayList<String>();
		expected.add("3");
		expected.add("9");
		expected.add("7");
		expected.add("7");
		expected.add("9");
		expected.add("7");
		expected.add("19");
		expected.add("3");
		expected.add("9");
		expected.add("7");
		expected.add("7");
		expected.add("9");
		expected.add("7");
		expected.add("19");

		CoGroupedWindowReduceInvokable<Tuple2<String, Integer>, Tuple2<String, Integer>, String> invokable = new CoGroupedWindowReduceInvokable<Tuple2<String, Integer>, Tuple2<String, Integer>, String>(
				new MyCoReduceFunction(), 3L, 3L, 2L, 2L, 0, 0,
				new MyTimeStamp<Tuple2<String, Integer>>(timestamps),
				new MyTimeStamp<Tuple2<String, Integer>>(timestamps));

		List<String> result = MockCoInvokable.createAndExecute(invokable, inputs1, inputs2);

		Collections.sort(result);
		Collections.sort(expected);
		assertEquals(expected, result);
	}

}
