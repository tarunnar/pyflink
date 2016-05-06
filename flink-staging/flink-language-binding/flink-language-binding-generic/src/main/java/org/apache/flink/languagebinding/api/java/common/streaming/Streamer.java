/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.flink.languagebinding.api.java.common.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Iterator;

import org.apache.flink.api.common.functions.AbstractRichFunction;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.io.RichInputFormat;
import org.apache.flink.api.common.io.RichOutputFormat;
import org.apache.flink.configuration.Configuration;

import static org.apache.flink.languagebinding.api.java.common.PlanBinder.PLANBINDER_CONFIG_BCVAR_COUNT;
import static org.apache.flink.languagebinding.api.java.common.PlanBinder.PLANBINDER_CONFIG_BCVAR_NAME_PREFIX;

import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the basis for using an external process within a Java Flink operator. It contains logic to send and
 * receive data, while taking care of synchronization.
 */
public abstract class Streamer implements Serializable {
	protected static final Logger LOG = LoggerFactory.getLogger(Streamer.class);
	private static final int SIGNAL_BUFFER_REQUEST = 0;
	private static final int SIGNAL_BUFFER_REQUEST_G0 = -3;
	private static final int SIGNAL_BUFFER_REQUEST_G1 = -4;
	private static final int SIGNAL_FINISHED = -1;
	private static final int SIGNAL_ERROR = -2;
	private static final byte SIGNAL_LAST = 32;

	private final byte[] buffer = new byte[4];

	protected ServerSocket server;
	protected Socket socket;
	protected InputStream in;
	protected OutputStream out;
	protected int port;

	protected Sender sender;
	protected Receiver receiver;

	protected StringBuilder msg = new StringBuilder();

	//TODO: refactor this since both variables are just here to provide access to a runtime context
	//however, at the time of construction this context is unavailable
	protected /*final*/ AbstractRichFunction function;
	protected RichInputFormat inputFormat;
	protected RichOutputFormat outputFormat;
	
	protected RuntimeContext context;
	protected boolean atJobManager;

	public Streamer(AbstractRichFunction function) {
		this.function = function;
		sender = new Sender(function);
		receiver = new Receiver(function);
	}
	
	public Streamer(RichInputFormat format, boolean atJobManager) {
		this.inputFormat = format;
		this.sender = new Sender();
		this.receiver = new Receiver();
		this.atJobManager = atJobManager;
	}

	public Streamer(RichOutputFormat format) {
		this.outputFormat = format;
		this.sender = new Sender();
		this.receiver = new Receiver();
	}



	public void open() throws IOException {
		server = new ServerSocket(0);
		setupProcess();
	}

	/**
	 * This method opens all required resources-
	 *
	 * @throws IOException
	 */
	public abstract void setupProcess() throws IOException;

	/**
	 * This method closes all previously opened resources.
	 *
	 * @throws IOException
	 */
	public void close() throws IOException {
		socket.close();
		sender.close();
		receiver.close();
	}

	private void sendWriteNotification(int size, boolean hasNext) throws IOException {
		byte[] tmp = new byte[5];
		putInt(tmp, 0, size);
		tmp[4] = hasNext ? 0 : SIGNAL_LAST;
		out.write(tmp, 0, 5);
		out.flush();
	}

	private void sendReadConfirmation() throws IOException {
		out.write(new byte[1], 0, 1);
		out.flush();
	}

	private void checkForError() {
		this.setContext();
		if (getInt(buffer, 0) == -2) {
			try { //wait before terminating to ensure that the complete error message is printed
				Thread.sleep(2000);
			} catch (InterruptedException ex) {
			}
			throw new RuntimeException(
					//"External process for task " + function.getRuntimeContext().getTaskName() + " terminated prematurely." + msg);
					"External process for task " + this.context.getTaskName() + " terminated prematurely." + msg);
		}
	}

	/**
	 * Sends all broadcast-variables encoded in the configuration to the external process.
	 *
	 * @param config configuration object containing broadcast-variable count and names
	 * @throws IOException
	 */
	public final void sendBroadCastVariables(Configuration config) throws IOException {
		this.setContext();
		try {
			int broadcastCount = config.getInteger(PLANBINDER_CONFIG_BCVAR_COUNT, 0);

			String[] names = new String[broadcastCount];

			for (int x = 0; x < names.length; x++) {
				names[x] = config.getString(PLANBINDER_CONFIG_BCVAR_NAME_PREFIX + x, null);
			}

			in.read(buffer, 0, 4);
			checkForError();
			int size = sender.sendRecord(broadcastCount);
			sendWriteNotification(size, false);

			for (String name : names) {
				Iterator bcv = this.context.getBroadcastVariable(name).iterator();

				in.read(buffer, 0, 4);
				checkForError();
				size = sender.sendRecord(name);
				sendWriteNotification(size, false);

				while (bcv.hasNext() || sender.hasRemaining(0)) {
					in.read(buffer, 0, 4);
					checkForError();
					size = sender.sendBuffer(bcv, 0);
					sendWriteNotification(size, bcv.hasNext() || sender.hasRemaining(0));
				}
				sender.reset();
			}
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + this.context.getTaskName() + " stopped responding." + msg);
		}
	}

	public final void sendMessage(String closeMessage) throws IOException {
		this.setContext();
		try {
			in.read(buffer, 0, 4);
			checkForError();
			int size = sender.sendRecord(closeMessage);
			sendWriteNotification(size, false);
			sender.reset();
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + this.context.getTaskName() + " stopped responding." + msg);
		}
	}

	/**
	 * Sends all values contained in the iterator to the external process and collects all results.
	 *
	 * @param i iterator
	 * @param c collector
	 * @throws IOException
	 */
	public final void streamBufferWithoutGroups(Iterator i, Collector c) throws IOException {
		this.setContext();
		try {
			int size;
			if (i.hasNext()) {
				while (true) {
					in.read(buffer, 0, 4);
					int sig = getInt(buffer, 0);
					switch (sig) {
						case SIGNAL_BUFFER_REQUEST:
							if (i.hasNext() || sender.hasRemaining(0)) {
								size = sender.sendBuffer(i, 0);
								sendWriteNotification(size, sender.hasRemaining(0) || i.hasNext());
							} else {
								throw new RuntimeException("External process requested data even though none is available.");
							}
							break;
						case SIGNAL_FINISHED:
							return;
						case SIGNAL_ERROR:
							try { //wait before terminating to ensure that the complete error message is printed
								Thread.sleep(2000);
							} catch (InterruptedException ex) {
							}
							throw new RuntimeException(
									"External process for task " + this.context.getTaskName() + " terminated prematurely due to an error." + msg);
						default:
							receiver.collectBuffer(c, sig);
							sendReadConfirmation();
							break;
					}
				}
			}
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + this.context.getTaskName() + " stopped responding." + msg);
		}
	}

	/**
	 * Sends all values contained in both iterators to the external process and collects all results.
	 *
	 * @param i1 iterator
	 * @param i2 iterator
	 * @param c collector
	 * @throws IOException
	 */
	public final void streamBufferWithGroups(Iterator i1, Iterator i2, Collector c) throws IOException {
		this.setContext();
		try {
			int size;
			if (i1.hasNext() || i2.hasNext()) {
				while (true) {
					in.read(buffer, 0, 4);
					int sig = getInt(buffer, 0);
					switch (sig) {
						case SIGNAL_BUFFER_REQUEST_G0:
							if (i1.hasNext() || sender.hasRemaining(0)) {
								size = sender.sendBuffer(i1, 0);
								sendWriteNotification(size, sender.hasRemaining(0) || i1.hasNext());
							}
							break;
						case SIGNAL_BUFFER_REQUEST_G1:
							if (i2.hasNext() || sender.hasRemaining(1)) {
								size = sender.sendBuffer(i2, 1);
								sendWriteNotification(size, sender.hasRemaining(1) || i2.hasNext());
							}
							break;
						case SIGNAL_FINISHED:
							return;
						case SIGNAL_ERROR:
							try { //wait before terminating to ensure that the complete error message is printed
								Thread.sleep(2000);
							} catch (InterruptedException ex) {
							}
							throw new RuntimeException(
									"External process for task " + this.context.getTaskName() + " terminated prematurely due to an error." + msg);
						default:
							receiver.collectBuffer(c, sig);
							sendReadConfirmation();
							break;
					}
				}
			}
		} catch (SocketTimeoutException ste) {
			throw new RuntimeException("External process for task " + this.context.getTaskName() + " stopped responding." + msg);
		}
	}

	protected final static int getInt(byte[] array, int offset) {
		return (array[offset] << 24) | (array[offset + 1] & 0xff) << 16 | (array[offset + 2] & 0xff) << 8 | (array[offset + 3] & 0xff);
	}

	protected final static void putInt(byte[] array, int offset, int value) {
		array[offset] = (byte) (value >> 24);
		array[offset + 1] = (byte) (value >> 16);
		array[offset + 2] = (byte) (value >> 8);
		array[offset + 3] = (byte) (value);
	}
	
	/**
	 * Checks whether this streamer belongs to a data source or a function, extracts the runtime context and 
	 * stores it into this.context.
	 */
	protected void setContext(){
		if(this.atJobManager || this.context != null)
		{
			return;
		}
		
		if(this.function != null)
		{
			this.context = this.function.getRuntimeContext();
			return;
		}
		
		if(this.inputFormat != null)
		{
			this.context = this.inputFormat.getRuntimeContext();
			return;
		}

		if(this.outputFormat != null) {
			this.context = this.outputFormat.getRuntimeContext();
			return;
		}
	}

}