/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.coyote.http11;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;

import org.apache.coyote.ByteBufferHolder;
import org.apache.coyote.Response;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.NioChannel;
import org.apache.tomcat.util.net.NioEndpoint;
import org.apache.tomcat.util.net.NioSelectorPool;
import org.apache.tomcat.util.net.SocketWrapperBase;

/**
 * Output buffer.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 */
public class InternalNioOutputBuffer extends AbstractOutputBuffer<NioChannel> {

    // ----------------------------------------------------------- Constructors

    /**
     * Default constructor.
     */
    public InternalNioOutputBuffer(Response response, int headerBufferSize) {
        super(response, headerBufferSize);
    }


    /**
     * Underlying socket.
     */
    private NioChannel socket;

    /**
     * Selector pool, for blocking reads and blocking writes
     */
    private NioSelectorPool pool;


    // --------------------------------------------------------- Public Methods

    @Override
    public void init(SocketWrapperBase<NioChannel> socketWrapper,
            AbstractEndpoint<NioChannel> endpoint) throws IOException {

        socket = socketWrapper.getSocket();
        pool = ((NioEndpoint)endpoint).getSelectorPool();
        socketWriteBuffer = socket.getBufHandler().getWriteBuffer();
    }


    /**
     * Recycle the output buffer. This should be called when closing the
     * connection.
     */
    @Override
    public void recycle() {
        super.recycle();
        socketWriteBuffer.clear();
        socket = null;
    }


    // ------------------------------------------------ HTTP/1.1 Output Methods

    /**
     * Send an acknowledgment.
     */
    @Override
    public void sendAck() throws IOException {
        if (!committed) {
            socketWriteBuffer.put(Constants.ACK_BYTES, 0, Constants.ACK_BYTES.length);
            int result = writeToSocket(socketWriteBuffer, true, true);
            if (result < 0) {
                throw new IOException(sm.getString("iob.failedwrite.ack"));
            }
        }
    }

    /**
     *
     * @param bytebuffer ByteBuffer
     * @param flip boolean
     * @return int
     * @throws IOException
     */
    private synchronized int writeToSocket(ByteBuffer bytebuffer, boolean block, boolean flip) throws IOException {
        if ( flip ) {
            bytebuffer.flip();
            writeBufferFlipped = true;
        }

        int written = 0;
        NioEndpoint.NioSocketWrapper att = (NioEndpoint.NioSocketWrapper)socket.getAttachment();
        if ( att == null ) throw new IOException("Key must be cancelled");
        long writeTimeout = att.getWriteTimeout();
        Selector selector = null;
        try {
            selector = pool.get();
        } catch ( IOException x ) {
            //ignore
        }
        try {
            written = pool.write(bytebuffer, socket, selector, writeTimeout, block);
            //make sure we are flushed
            do {
                if (socket.flush(true,selector,writeTimeout)) break;
            }while ( true );
        } finally {
            if ( selector != null ) pool.put(selector);
        }
        if ( block || bytebuffer.remaining()==0) {
            //blocking writes must empty the buffer
            //and if remaining==0 then we did empty it
            bytebuffer.clear();
            writeBufferFlipped = false;
        }
        // If there is data left in the buffer the socket will be registered for
        // write further up the stack. This is to ensure the socket is only
        // registered for write once as both container and user code can trigger
        // write registration.
        return written;
    }


    // ------------------------------------------------------ Protected Methods

    @Override
    protected synchronized void addToBB(byte[] buf, int offset, int length)
            throws IOException {

        if (length == 0) return;

        // Try to flush any data in the socket's write buffer first
        boolean dataLeft = flushBuffer(isBlocking());

        // Keep writing until all the data is written or a non-blocking write
        // leaves data in the buffer
        while (!dataLeft && length > 0) {
            int thisTime = transfer(buf,offset,length,socketWriteBuffer);
            length = length - thisTime;
            offset = offset + thisTime;
            int written = writeToSocket(socketWriteBuffer, isBlocking(), true);
            if (written == 0) {
                dataLeft = true;
            } else {
                dataLeft = flushBuffer(isBlocking());
            }
        }

        NioEndpoint.NioSocketWrapper ka = (NioEndpoint.NioSocketWrapper)socket.getAttachment();
        if (ka != null) ka.access();//prevent timeouts for just doing client writes

        if (!isBlocking() && length > 0) {
            // Remaining data must be buffered
            addToBuffers(buf, offset, length);
        }
    }


    private void addToBuffers(byte[] buf, int offset, int length) {
        ByteBufferHolder holder = bufferedWrites.peekLast();
        if (holder==null || holder.isFlipped() || holder.getBuf().remaining()<length) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.max(bufferedWriteSize,length));
            holder = new ByteBufferHolder(buffer,false);
            bufferedWrites.add(holder);
        }
        holder.getBuf().put(buf,offset,length);
    }


    /**
     * Callback to write data from the buffer.
     */
    @Override
    protected boolean flushBuffer(boolean block) throws IOException {

        //prevent timeout for async,
        SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
        if (key != null) {
            NioEndpoint.NioSocketWrapper attach = (NioEndpoint.NioSocketWrapper) key.attachment();
            attach.access();
        }

        boolean dataLeft = hasMoreDataToFlush();

        //write to the socket, if there is anything to write
        if (dataLeft) {
            writeToSocket(socketWriteBuffer, block, !writeBufferFlipped);
        }

        dataLeft = hasMoreDataToFlush();

        if (!dataLeft && bufferedWrites.size() > 0) {
            Iterator<ByteBufferHolder> bufIter = bufferedWrites.iterator();
            while (!hasMoreDataToFlush() && bufIter.hasNext()) {
                ByteBufferHolder buffer = bufIter.next();
                buffer.flip();
                while (!hasMoreDataToFlush() && buffer.getBuf().remaining()>0) {
                    transfer(buffer.getBuf(), socketWriteBuffer);
                    if (buffer.getBuf().remaining() == 0) {
                        bufIter.remove();
                    }
                    writeToSocket(socketWriteBuffer, block, true);
                    //here we must break if we didn't finish the write
                }
            }
        }

        return hasMoreDataToFlush();
    }


    @Override
    protected void registerWriteInterest() throws IOException {
        NioEndpoint.NioSocketWrapper att = (NioEndpoint.NioSocketWrapper)socket.getAttachment();
        if (att == null) {
            throw new IOException("Key must be cancelled");
        }
        att.getPoller().add(socket, SelectionKey.OP_WRITE);
    }
}
