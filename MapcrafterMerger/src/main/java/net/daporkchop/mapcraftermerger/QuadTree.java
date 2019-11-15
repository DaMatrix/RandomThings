/*
 * Adapted from the Wizardry License
 *
 * Copyright (c) 2019-2019 DaPorkchop_ and contributors
 *
 * Permission is hereby granted to any persons and/or organizations using this software to copy, modify, merge, publish, and distribute it. Said persons and/or organizations are not allowed to use the software or any derivatives of the work for commercial use or any other means to generate income, nor are they allowed to claim this software as their own.
 *
 * The persons and/or organizations are also disallowed from sub-licensing and/or trademarking this software without explicit permission from DaPorkchop_.
 *
 * Any persons and/or organizations using this software must disclose their source code and have it publicly available, include this license, provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package net.daporkchop.mapcraftermerger;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Iterator;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

import static net.daporkchop.lib.math.primitive.PMath.max;

enum Sector {
    TOP_LEFT(),
    TOP_RIGHT(),
    BOTTOM_LEFT(),
    BOTTOM_RIGHT();

    public static final Sector[] VALUES = values();

    public static Sector fromIndex(int i) {
        return VALUES[i];
    }

    public static Sector fromOffsetIndex(int i) {
        return VALUES[i - 1];
    }

    public int offsetIndex() {
        return this.ordinal() + 1;
    }
}

/**
 * @author DaPorkchop_
 */
@Accessors(fluent = true)
public final class QuadTree<V> extends Node<V> {
    static <E> Stack<E> copy(@NonNull Stack<E> src) {
        Stack<E> dst = new Stack<>();
        src.forEach(dst::push);
        return dst;
    }
    private final Node<V> delegate;
    private final Lock readLock;
    private final Lock writeLock;

    public QuadTree() {
        this.delegate = new ReferenceNode<>(null);

        ReadWriteLock lock = new ReentrantReadWriteLock();
        this.readLock = lock.readLock();
        this.writeLock = lock.writeLock();
    }

    @Override
    public Node<V> parent() {
        return null;
    }

    @Override
    public boolean hasValue() {
        return this.delegate.hasValue();
    }

    @Override
    public V value() {
        return this.delegate.value();
    }

    @Override
    public void value(@NonNull V value) {
        this.delegate.value(value);
    }

    @Override
    public Node<V> getChild(int index) {
        return this.delegate.getChild(index);
    }

    @Override
    public void setChild(int index, Node<V> node) {
        this.delegate.setChild(index, node);
    }

    public int depth() {
        this.readLock.lock();
        try {
            return this.depthRecursive(this.delegate, 0);
        } finally {
            this.readLock.unlock();
        }
    }

    private int depthRecursive(Node<V> node, int depth) {
        if (node == null || node.hasValue()) {
            return depth;
        } else {
            return max(max(
                    this.depthRecursive(node.getChild(0), depth + 1),
                    this.depthRecursive(node.getChild(1), depth + 1)
            ), max(
                    this.depthRecursive(node.getChild(2), depth + 1),
                    this.depthRecursive(node.getChild(3), depth + 1)
            ));
        }
    }

    public void forEachValue(@NonNull BiConsumer<Stack<Integer>, V> callback) {
        this.readLock.lock();
        try {
            this.forEachValueRecursive(callback, new Stack<>(), this.delegate);
        } finally {
            this.readLock.unlock();
        }
    }

    private void forEachValueRecursive(@NonNull BiConsumer<Stack<Integer>, V> callback, @NonNull Stack<Integer> stack, Node<V> node) {
        if (node != null) {
            if (node.hasValue()) {
                callback.accept(copy(stack), node.value());
            } else {
                for (Sector sector : Sector.VALUES) {
                    stack.push(sector.offsetIndex());
                    this.forEachValueRecursive(callback, stack, node.getChild(sector.ordinal()));
                    stack.pop();
                }
            }
        }
    }

    public void forEachValueAtDepth(int depth, @NonNull BiConsumer<Stack<Integer>, V> callback) {
        this.readLock.lock();
        try {
            this.forEachValueAtDepthRecursive(depth, 0, callback, new Stack<>(), this.delegate);
        } finally {
            this.readLock.unlock();
        }
    }

    private void forEachValueAtDepthRecursive(int targetDepth, int depth, @NonNull BiConsumer<Stack<Integer>, V> callback, @NonNull Stack<Integer> stack, Node<V> node) {
        if (node != null) {
            if (depth == targetDepth && node.hasValue()) {
                callback.accept(copy(stack), node.value());
            } else if (depth < targetDepth && !node.hasValue()) {
                for (Sector sector : Sector.VALUES) {
                    stack.push(sector.offsetIndex());
                    this.forEachValueAtDepthRecursive(targetDepth, depth + 1, callback, stack, node.getChild(sector.ordinal()));
                    stack.pop();
                }
            }
        }
    }

    public boolean set(@NonNull List<Integer> stack, V value) {
        this.writeLock.lock();
        try {
            Node<V> node = this.delegate;
            for (Iterator<Integer> itr = stack.iterator(); itr.hasNext(); ) {
                int index = itr.next() - 1;
                if (node.hasValue()) {
                    if (itr.hasNext()) {
                        return false; //don't set anything if a higher node already has a value value
                    } else {
                        node.value(value);
                        return true;
                    }
                } else if (itr.hasNext()) {
                    if (node.getChild(index) == null) {
                        node.setChild(index, new ReferenceNode<>(node));
                    }
                    node = node.getChild(index);
                } else {
                    node.setChild(index, new ValueNode<>(node, value));
                    return true;
                }
            }
            throw new IllegalStateException();
        } finally {
            this.writeLock.unlock();
        }
    }

    @Override
    public String toString() {
        this.readLock.lock();
        try {
            StringBuilder builder = new StringBuilder();
            this.toStringRecursive(builder, this.delegate, 0);
            return builder.toString();
        } finally {
            this.readLock.unlock();
        }
    }

    private void toStringRecursive(@NonNull StringBuilder builder, Node<V> node, int depth) {
        if (node == null) {
            builder.append("null");
        } else if (node.hasValue()) {
            builder/*.append(depth)*/.append("value[").append(node.value()).append(']');
        } else {
            builder.append("node[");
            this.toStringRecursive(this.newline(builder, depth + 1), node.getChild(0), depth + 1);
            this.toStringRecursive(this.newline(builder.append(','), depth + 1), node.getChild(1), depth + 1);
            this.toStringRecursive(this.newline(builder.append(','), depth + 1), node.getChild(2), depth + 1);
            this.toStringRecursive(this.newline(builder.append(','), depth + 1), node.getChild(3), depth + 1);
            this.newline(builder, depth).append(']');
        }
    }

    private StringBuilder newline(@NonNull StringBuilder builder, int depth) {
        this.indent(builder.append('\n'), depth);
        return builder;
    }

    private void indent(@NonNull StringBuilder builder, int count) {
        while (--count >= 0) {
            builder.append(' ');
        }
    }
}

@RequiredArgsConstructor
final class ReferenceNode<V> extends Node<V> {
    @Getter
    @Accessors(fluent = true)
    private final Node<V> parent;

    @SuppressWarnings("unchecked")
    private final Node<V>[] children = (Node<V>[]) new Node[4];

    @Override
    public boolean hasValue() {
        return false;
    }

    @Override
    public V value() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void value(@NonNull V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Node<V> getChild(int index) {
        return this.children[index];
    }

    @Override
    public void setChild(int index, Node<V> node) {
        this.children[index] = node;
    }
}

@RequiredArgsConstructor
@Getter
@Accessors(fluent = true, chain = false)
final class ValueNode<V> extends Node<V> {
    private final Node<V> parent;

    @Setter
    @NonNull
    protected V value;

    @Override
    public boolean hasValue() {
        return true;
    }

    @Override
    public Node<V> getChild(int index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setChild(int index, Node<V> node) {
        throw new UnsupportedOperationException();
    }
}

abstract class Node<V> {
    public abstract Node<V> parent();

    public abstract boolean hasValue();

    public abstract V value();

    public abstract void value(@NonNull V value);

    public Node<V> getChild(@NonNull Sector sector) {
        return this.getChild(sector.ordinal());
    }

    public abstract Node<V> getChild(int index);

    public abstract void setChild(int index, Node<V> node);
}
