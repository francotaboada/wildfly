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
package org.jboss.as.test.clustering.single.ejb.bean;

import java.io.Serializable;

/**
 * A wrapper for a return value that includes the node on which the result was generated.
 * @author Paul Ferraro
 */
public class Result<T> implements Serializable {
    private static final long serialVersionUID = -1079933234795356933L;

    private final T value;
    private final String node;

    public Result(T value) {
        this.value = value;
        this.node = System.getProperty("jboss.node.name");
    }

    public T getValue() {
        return this.value;
    }

    public String getNode() {
        return this.node;
    }
}
