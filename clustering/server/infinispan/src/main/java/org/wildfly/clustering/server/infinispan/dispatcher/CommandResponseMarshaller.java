/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.clustering.server.infinispan.dispatcher;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Predicate;

import org.wildfly.clustering.marshalling.spi.IndexSerializer;
import org.wildfly.clustering.marshalling.spi.ByteBufferMarshaller;

/**
 * Marshalling strategy for the command response.
 * @author Paul Ferraro
 */
public class CommandResponseMarshaller implements org.jgroups.blocks.Marshaller {
    private final ByteBufferMarshaller marshaller;
    private final Predicate<ByteBuffer> unknownForkPredicate;

    CommandResponseMarshaller(ChannelCommandDispatcherFactoryConfiguration config) {
        this.marshaller = config.getMarshaller();
        this.unknownForkPredicate = config.getUnknownForkPredicate();
    }

    @Override
    public void objectToStream(Object object, DataOutput stream) throws IOException {
        ByteBuffer buffer = this.marshaller.write(object);

        int length = buffer.limit() - buffer.arrayOffset();
        IndexSerializer.VARIABLE.writeInt(stream, length);
        stream.write(buffer.array(), buffer.arrayOffset(), length);
    }

    @Override
    public Object objectFromStream(DataInput stream) throws IOException {
        int size = IndexSerializer.VARIABLE.readInt(stream);
        byte[] bytes = new byte[size];
        stream.readFully(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return this.unknownForkPredicate.test(buffer) ? NoSuchService.INSTANCE : this.marshaller.read(buffer);
    }
}
