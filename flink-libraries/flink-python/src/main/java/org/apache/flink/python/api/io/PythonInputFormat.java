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

package org.apache.flink.python.api.io;

import org.apache.flink.api.common.io.FileInputFormat;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileInputSplit;
import org.apache.flink.core.fs.FileStatus;
import org.apache.flink.core.fs.Path;

import java.io.IOException;

/**
 */
public class PythonInputFormat<T> extends FileInputFormat<T> implements ResultTypeQueryable<T> {
	private TypeInformation<T> typeInformation;
	private boolean splitsInPython;

	private final PythonInputSplitGenerator splitGenerator;
	private final PythonInputSplitProcessor<T> splitProcessor;

	private boolean openedProcessor = false;

	private String tmpPath;

	public PythonInputFormat(Path path, int id, TypeInformation<T> info, String filter, boolean computeSplitsInPython, String tmpPath) {
		super(path);
		this.splitGenerator = new PythonInputSplitGenerator(id, path, filter, tmpPath);
		this.splitProcessor = new PythonInputSplitProcessor<>(this, id, info instanceof PrimitiveArrayTypeInfo, tmpPath);
		this.typeInformation = info;
		this.splitsInPython = computeSplitsInPython;
		this.tmpPath = tmpPath;
	}

	//==================================================================================================================
	// Split Generator
	//==================================================================================================================

	@Override
	public FileInputSplit[] createInputSplits(int minNumSplits) throws IOException {
		if (this.splitsInPython) {
			return this.splitGenerator.createInputSplits(numSplits);
		} else {
			FileInputSplit[] inputSplits = super.createInputSplits(minNumSplits);
			return inputSplits;
		}
	}

	@Override
	protected boolean acceptFile(FileStatus fileStatus) {
		return this.splitGenerator.acceptFile(fileStatus) && super.acceptFile(fileStatus);
	}

	//==================================================================================================================
	// Split Processor
	//==================================================================================================================

	@Override
	public void configure(Configuration parameters) {
		super.configure(parameters);
		this.splitProcessor.configure(parameters);
	}

	@Override
	public void openInputFormat() {
		super.openInputFormat();
	}

	@Override
	public void open(FileInputSplit split) throws IOException {
		if(!this.openedProcessor) {
			try {
				this.splitProcessor.open();
			} catch (IOException e) {
				e.printStackTrace();
			}
			this.openedProcessor = true;
		}
		this.splitProcessor.openSplit(split);
	}

	@Override
	public void close() throws IOException {
		if(this.openedProcessor) {
			this.splitProcessor.closeSplit();
		}
	}

	@Override
	public boolean reachedEnd() throws IOException {
		if(this.openedProcessor) {
			return this.splitProcessor.reachedEnd();
		}else {
			return true;
		}

	}

	@Override
	public T nextRecord(T record) throws IOException {
		return this.splitProcessor.nextRecord(record);
	}

	@Override
	public void closeInputFormat() {
		super.closeInputFormat();
		if(this.openedProcessor) {
			this.splitProcessor.close();
		}
	}

	@Override
	public TypeInformation<T> getProducedType() {
		return this.typeInformation;
	}
}
