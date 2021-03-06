/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.compression;

import java.io.InputStream;
import java.util.zip.Checksum;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static io.netty.handler.codec.compression.Lz4Constants.DEFAULT_SEED;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class Lz4FrameEncoderTest extends AbstractEncoderTest {
    /**
     * For the purposes of this test, if we pass this (very small) size of buffer into
     * {@link Lz4FrameEncoder#allocateBuffer(ChannelHandlerContext, ByteBuf, boolean)}, we should get back
     * an empty buffer.
     */
    private static final int NONALLOCATABLE_SIZE = 1;

    @Mock
    private ChannelHandlerContext ctx;

    /**
     * A {@link ByteBuf} for mocking purposes, largely because it's difficult to allocate to huge buffers.
     */
    @Mock
    private ByteBuf buffer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(ctx.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
    }

    @Override
    public void initChannel() {
        channel = new EmbeddedChannel(new Lz4FrameEncoder());
    }

    @Override
    protected ByteBuf decompress(ByteBuf compressed, int originalLength) throws Exception {
        InputStream is = new ByteBufInputStream(compressed, true);
        LZ4BlockInputStream lz4Is = null;
        byte[] decompressed = new byte[originalLength];
        try {
            lz4Is = new LZ4BlockInputStream(is);
            int remaining = originalLength;
            while (remaining > 0) {
                int read = lz4Is.read(decompressed, originalLength - remaining, remaining);
                if (read > 0) {
                    remaining -= read;
                } else {
                    break;
                }
            }
            assertEquals(-1, lz4Is.read());
        } finally {
            if (lz4Is != null) {
                lz4Is.close();
            } else {
                is.close();
            }
        }

        return Unpooled.wrappedBuffer(decompressed);
    }

    @Test
    public void testAllocateDirectBuffer() {
        final int blockSize = 100;
        testAllocateBuffer(blockSize, blockSize - 13, true);
        testAllocateBuffer(blockSize, blockSize * 5, true);
        testAllocateBuffer(blockSize, NONALLOCATABLE_SIZE, true);
    }

    @Test
    public void testAllocateHeapBuffer() {
        final int blockSize = 100;
        testAllocateBuffer(blockSize, blockSize - 13, false);
        testAllocateBuffer(blockSize, blockSize * 5, false);
        testAllocateBuffer(blockSize, NONALLOCATABLE_SIZE, false);
    }

    private void testAllocateBuffer(int blockSize, int bufSize, boolean isDirect) {
        // allocate the input buffer to an arbitrary size less than the blockSize
        ByteBuf in = ByteBufAllocator.DEFAULT.buffer(bufSize, bufSize);
        in.writerIndex(in.capacity());

        ByteBuf out = null;
        try {
            Lz4FrameEncoder encoder = newEncoder(blockSize, Lz4FrameEncoder.DEFAULT_MAX_ENCODE_SIZE);
            out = encoder.allocateBuffer(ctx, in, isDirect);
            Assert.assertNotNull(out);
            if (NONALLOCATABLE_SIZE == bufSize) {
                Assert.assertFalse(out.isWritable());
            } else {
                Assert.assertTrue(out.writableBytes() > 0);
                Assert.assertEquals(isDirect, out.isDirect());
            }
        } finally {
            in.release();
            if (out != null) {
                out.release();
            }
        }
    }

    @Test (expected = EncoderException.class)
    public void testAllocateDirectBufferExceedMaxEncodeSize() {
        final int maxEncodeSize = 1024;
        Lz4FrameEncoder encoder = newEncoder(Lz4Constants.DEFAULT_BLOCK_SIZE, maxEncodeSize);
        int inputBufferSize = maxEncodeSize * 10;
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(inputBufferSize, inputBufferSize);
        try {
            buf.writerIndex(inputBufferSize);
            encoder.allocateBuffer(ctx, buf, false);
        } finally {
            buf.release();
        }
    }

    private Lz4FrameEncoder newEncoder(int blockSize, int maxEncodeSize) {
        Checksum checksum = XXHashFactory.fastestInstance().newStreamingHash32(DEFAULT_SEED).asChecksum();
        Lz4FrameEncoder encoder = new Lz4FrameEncoder(LZ4Factory.fastestInstance(), true,
                                                      blockSize,
                                                      checksum,
                                                      maxEncodeSize);
        encoder.handlerAdded(ctx);
        return encoder;
    }

    /**
     * This test might be a invasive in terms of knowing what happens inside
     * {@link Lz4FrameEncoder#allocateBuffer(ChannelHandlerContext, ByteBuf, boolean)}, but this is safest way
     * of testing the overflow conditions as allocating the huge buffers fails in many CI environments.
     */
    @Test (expected = EncoderException.class)
    public void testAllocateOnHeapBufferOverflowsOutputSize() {
        final int maxEncodeSize = Integer.MAX_VALUE;
        Lz4FrameEncoder encoder = newEncoder(Lz4Constants.DEFAULT_BLOCK_SIZE, maxEncodeSize);
        when(buffer.readableBytes()).thenReturn(maxEncodeSize);
        buffer.writerIndex(maxEncodeSize);
        encoder.allocateBuffer(ctx, buffer, false);
    }

    @Test
    public void testFlush() {
        Lz4FrameEncoder encoder = new Lz4FrameEncoder();
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        int size = 27;
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(size, size);
        buf.writerIndex(size);
        Assert.assertEquals(0, encoder.getBackingBuffer().readableBytes());
        channel.write(buf);
        Assert.assertTrue(channel.outboundMessages().isEmpty());
        Assert.assertEquals(size, encoder.getBackingBuffer().readableBytes());
        channel.flush();
        Assert.assertTrue(channel.finish());
        Assert.assertTrue(channel.releaseOutbound());
        Assert.assertFalse(channel.releaseInbound());
    }

    @Test
    public void testAllocatingAroundBlockSize() {
        int blockSize = 100;
        Lz4FrameEncoder encoder = newEncoder(blockSize, Lz4FrameEncoder.DEFAULT_MAX_ENCODE_SIZE);
        EmbeddedChannel channel = new EmbeddedChannel(encoder);

        int size = blockSize - 1;
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer(size, size);
        buf.writerIndex(size);
        Assert.assertEquals(0, encoder.getBackingBuffer().readableBytes());
        channel.write(buf);
        Assert.assertEquals(size, encoder.getBackingBuffer().readableBytes());

        int nextSize = size - 1;
        buf = ByteBufAllocator.DEFAULT.buffer(nextSize, nextSize);
        buf.writerIndex(nextSize);
        channel.write(buf);
        Assert.assertEquals(size + nextSize - blockSize, encoder.getBackingBuffer().readableBytes());

        channel.flush();
        Assert.assertEquals(0, encoder.getBackingBuffer().readableBytes());
        Assert.assertTrue(channel.finish());
        Assert.assertTrue(channel.releaseOutbound());
        Assert.assertFalse(channel.releaseInbound());
    }
}
