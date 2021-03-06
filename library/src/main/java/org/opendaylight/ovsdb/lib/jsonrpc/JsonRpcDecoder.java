/*
 * Copyright (C) 2013 EBay Software Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Authors : Ashwin Raveendran, Madhu Venugopal
 */
package org.opendaylight.ovsdb.lib.jsonrpc;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;

import java.io.IOException;
import java.util.List;

import org.opendaylight.ovsdb.lib.error.InvalidEncodingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.ByteSourceJsonBootstrapper;
import com.fasterxml.jackson.core.util.BufferRecycler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingJsonFactory;

/**
 * JSON RPC 1.0 compatible decoder capable of decoding JSON messages from a TCP stream.
 * The stream is framed first by inspecting the json for valid end marker (left curly)
 * and is passed to a Json parser (jackson) for converting into an object model.
 *
 * There are no JSON parsers that I am aware of that does non blocking parsing.
 * This approach avoids having to run json parser over and over again on the entire
 * stream waiting for input. Parser is invoked only when we know of a full JSON message
 * in the stream.
 */
public class JsonRpcDecoder extends ByteToMessageDecoder {

    protected static final Logger logger = LoggerFactory.getLogger(JsonRpcDecoder.class);
    private int maxFrameLength;
    //Indicates if the frame limit warning was issued
    private boolean maxFrameLimitWasReached = false;
    private JsonFactory jacksonJsonFactory = new MappingJsonFactory();

    private IOContext jacksonIOContext = new IOContext(new BufferRecycler(), null, false);

    // context for the previously read incomplete records
    private int lastRecordBytes = 0;
    private int leftCurlies = 0;
    private int rightCurlies = 0;
    private boolean inS = false;

    private int recordsRead;

    public JsonRpcDecoder(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buf, List<Object> out) throws Exception {

        logger.trace("readable bytes {}, records read {}, incomplete record bytes {}",
                buf.readableBytes(), recordsRead, lastRecordBytes);

        if (lastRecordBytes == 0) {
            if (buf.readableBytes() < 4) {
                return; //wait for more data
            }

            skipSpaces(buf);

            byte[] buff = new byte[4];
            buf.getBytes(buf.readerIndex(), buff);
            ByteSourceJsonBootstrapper strapper = new ByteSourceJsonBootstrapper(jacksonIOContext, buff, 0, 4);
            JsonEncoding jsonEncoding = strapper.detectEncoding();
            if (!JsonEncoding.UTF8.equals(jsonEncoding)) {
                throw new InvalidEncodingException(jsonEncoding.getJavaName(), "currently only UTF-8 is supported");
            }
        }

        int index = lastRecordBytes + buf.readerIndex();

        for (; index < buf.writerIndex(); index++) {
            switch (buf.getByte(index)) {
                case '{':
                    if (!inS) {
                        leftCurlies++;
                    }
                    break;
                case '}':
                    if (!inS) {
                        rightCurlies++;
                    }
                    break;
                case '"':
                    if (buf.getByte(index - 1) != '\\') {
                        inS = !inS;
                    }
                    break;
                default:
                    break;
            }

            if (leftCurlies != 0 && leftCurlies == rightCurlies && !inS) {
                ByteBuf slice = buf.readSlice(1 + index - buf.readerIndex());
                JsonParser jp = jacksonJsonFactory.createParser(new ByteBufInputStream(slice));
                JsonNode root = jp.readValueAsTree();
                out.add(root);
                leftCurlies = rightCurlies = lastRecordBytes = 0;
                recordsRead++;
                break;
            }

            if (index - buf.readerIndex() >= maxFrameLength) {
                /*
                 * Changing this limit to being a warning, we do not wish to "break" in scale environment
                 * and currently this limits the ovs of having only around 50 ports defined...
                 * I do acknowledge the fast that this might be risky in case of huge amount of strings
                 * in which the controller can crash with an OOM, however seems that we need a really huge
                 * ovs to reach that limit.
                 */
                //fail(ctx, index - buf.readerIndex());

                //We do not want to issue a log message on every extent of the buffer
                //hence logging only once
                if (!maxFrameLimitWasReached) {
                    maxFrameLimitWasReached = true;
                    logger.warn("***** OVSDB Frame limit of " + this.maxFrameLength + " bytes has been reached! *****");
                }
            }
        }

        // end of stream, save the incomplete record index to avoid reexamining the whole on next run
        if (index >= buf.writerIndex()) {
            lastRecordBytes = buf.readableBytes();
            return;
        }
    }

    public int getRecordsRead() {
        return recordsRead;
    }

    private static void skipSpaces(ByteBuf byteBuf) throws IOException {
        while (byteBuf.isReadable()) {
            int ch = byteBuf.getByte(byteBuf.readerIndex()) & 0xFF;
            if (!(ch == ' ' || ch == '\r' || ch == '\n' || ch == '\t')) {
                return;
            } else {
                byteBuf.readByte(); //move the read index
            }
        }
    }


    private void print(ByteBuf buf, String message) {
        print(buf, buf.readerIndex(), buf.readableBytes(), message == null ? "buff" : message);
    }

    private void print(ByteBuf buf, int startPos, int chars, String message) {
        if (null == message) {
            message = "";
        }
        if (startPos > buf.writerIndex()) {
            logger.trace("startPos out of bounds");
        }
        byte[] bytes = new byte[startPos + chars <= buf.writerIndex() ? chars : buf.writerIndex() - startPos];
        buf.getBytes(startPos, bytes);
        logger.trace("{} ={}", message, new String(bytes));
    }

    // copied from Netty decoder
    private void fail(ChannelHandlerContext ctx, long frameLength) {
        if (frameLength > 0) {
            ctx.fireExceptionCaught(
                    new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength + ": " + frameLength + " - discarded"));
        } else {
            ctx.fireExceptionCaught(
                    new TooLongFrameException(
                            "frame length exceeds " + maxFrameLength + " - discarding"));
        }
    }
}
