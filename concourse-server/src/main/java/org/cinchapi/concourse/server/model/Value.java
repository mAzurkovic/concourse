/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013 Jeff Nelson, Cinchapi Software Collective
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.cinchapi.concourse.server.model;

import java.nio.ByteBuffer;
import java.util.Comparator;

import javax.annotation.concurrent.Immutable;

import org.cinchapi.concourse.annotate.DoNotInvoke;
import org.cinchapi.concourse.cache.ReferenceCache;
import org.cinchapi.concourse.server.io.Byteable;
import org.cinchapi.concourse.server.io.Byteables;
import org.cinchapi.concourse.thrift.TObject;
import org.cinchapi.concourse.thrift.Type;
import org.cinchapi.concourse.util.ByteBuffers;
import org.cinchapi.concourse.util.Convert;
import org.cinchapi.concourse.util.Numbers;

/**
 * A Value is a {@link Byteable} abstraction for a {@link TObject} that records
 * type information and serves as the most basic element of data in Concourse.
 * Values are logically sortable using weak typing and cannot exceed 2^32 bytes.
 * <p>
 * <h2>Storage Requirements</h2>
 * Each Value requires at least {@value #CONSTANT_SIZE} bytes of space in
 * addition to the following type specific requirements:
 * <ul>
 * <li>BOOLEAN requires an additional 1 byte</li>
 * <li>DOUBLE requires an additional 8 bytes</li>
 * <li>FLOAT requires an additional 4 bytes</li>
 * <li>INTEGER requires an additional 4 bytes</li>
 * <li>LONG requires an additional 8 bytes</li>
 * <li>LINK requires an additional 8 bytes</li>
 * <li>STRING requires an additional 14 bytes for every character (uses UTF8
 * encoding)</li>
 * </ul>
 * </p>
 * 
 * @author jnelson
 */
@Immutable
public final class Value implements Byteable, Comparable<Value> {

	/**
	 * Return the Value encoded in {@code bytes} so long as those bytes adhere
	 * to the format specified by the {@link #getBytes()} method. This method
	 * assumes that all the bytes in the {@code bytes} belong to the Value. In
	 * general, it is necessary to get the appropriate Value slice from the
	 * parent ByteBuffer using {@link ByteBuffers#slice(ByteBuffer, int, int)}.
	 * 
	 * @param bytes
	 * @return the Value
	 */
	public static Value fromByteBuffer(ByteBuffer bytes) {
		return Byteables.read(bytes, Value.class); // We are using
													// Byteables#read(ByteBuffer,
													// Class) instead of calling
													// the constructor directly
													// so as to take advantage
													// of the automatic
													// reference caching that is
													// provided in the utility
													// class
	}

	/**
	 * Return a Value that is backed by {@code data}.
	 * 
	 * @param data
	 * @return the Value
	 */
	public static Value wrap(TObject data) {
		Object[] cacheKey = getCacheKey(data);
		Value value = VALUE_CACHE.get(cacheKey);
		if(value == null) {
			value = new Value(data);
		}
		return value;
	}

	/**
	 * Return the Java object represented by {@code data}.
	 * 
	 * @param data
	 * @return the Object
	 */
	private static Object extractObjectAndCache(TObject data) {
		Object[] cacheKey = getCacheKey(data);
		Object object = OBJECT_CACHE.get(cacheKey);
		if(object == null) {
			object = Convert.thriftToJava(data);
			OBJECT_CACHE.put(object, cacheKey);
		}
		return object;
	}

	/**
	 * Return the {@link TObject} of {@code type} represented by {@code bytes}.
	 * This method reads the remaining bytes from the current position into the
	 * returned TObject.
	 * 
	 * @param bytes
	 * @param type
	 * @return the TObject
	 */
	private static TObject extractTObjectAndCache(ByteBuffer bytes, Type type) {
		Object[] cacheKey = { ByteBuffers.encodeAsHexString(bytes), type };
		TObject data = TOBJECT_CACHE.get(cacheKey);
		if(data == null) {
			// Must allocate a heap buffer because TObject assumes it has a
			// backing array and because of THRIFT-2104 that buffer must wrap a
			// byte array in order to assume that the TObject does not lose data
			// when transferred over the wire.
			byte[] array = new byte[bytes.remaining()];
			bytes.get(array); // We CANNOT simply slice {@code buffer} and use
								// the slice's backing array because the backing
								// array of the slice is the same as the
								// original, which contains more data than we
								// need for the quantity
			data = new TObject(ByteBuffer.wrap(array), type);
			TOBJECT_CACHE.put(data, cacheKey);
		}
		return data;
	}

	/**
	 * Return the cache key that corresponds to {@code data}.
	 * 
	 * @param data
	 * @return the cacheKey
	 */
	private static Object[] getCacheKey(TObject data) {
		return new Object[] {
				ByteBuffers.encodeAsHexString(data.bufferForData()),
				data.getType() };
	}

	/**
	 * The minimum number of bytes needed to encode every Value.
	 */
	private static final int CONSTANT_SIZE = 4; // type(4)

	/**
	 * The comparator that is used to sort values using weak typing.
	 */
	private static final Sorter SORTER = new Sorter();

	/**
	 * Cache to store references that have already been loaded in the JVM.
	 */
	private static final ReferenceCache<Object> OBJECT_CACHE = new ReferenceCache<Object>();

	/**
	 * Cache to store references that have already been loaded in the JVM.
	 */
	private static final ReferenceCache<TObject> TOBJECT_CACHE = new ReferenceCache<TObject>();

	/**
	 * Cache to store references that have already been loaded in the JVM.
	 */
	private static final ReferenceCache<Value> VALUE_CACHE = new ReferenceCache<Value>();

	/**
	 * The underlying data represented by this Value. This representation is
	 * used when serializing/deserializing the data for RPC or disk and network
	 * I/O.
	 */
	private final TObject data;

	/**
	 * A cached copy of the binary representation that is returned from
	 * {@link #getBytes()}.
	 */
	private transient ByteBuffer bytes = null;

	/**
	 * The java representation of the underlying {@link #data}. This
	 * representation is used when interacting with other components in the JVM.
	 */
	private final transient Object object;

	/**
	 * Construct an instance that represents an existing Value from a
	 * ByteBuffer. This constructor is public so as to comply with the
	 * {@link Byteable} interface. Calling this constructor directly is not
	 * recommend. Use {@link #fromByteBuffer(ByteBuffer)} instead to take
	 * advantage of reference caching.
	 * 
	 * @param bytes
	 */
	@DoNotInvoke
	public Value(ByteBuffer bytes) {
		this.bytes = bytes;
		Type type = Type.values()[bytes.getInt()];
		this.data = extractTObjectAndCache(bytes, type);
		this.object = extractObjectAndCache(data);
	}

	/**
	 * Construct a new instance.
	 * 
	 * @param data
	 */
	private Value(TObject data) {
		this.data = data;
		this.object = extractObjectAndCache(data);
	}

	@Override
	public int compareTo(Value other) {
		return SORTER.compare(this, other);
	}

	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Value) {
			final Value other = (Value) obj;
			return object.equals(other.object);
		}
		return false;
	}

	/**
	 * Return a byte buffer that represents this Value with the following order:
	 * <ol>
	 * <li><strong>type</strong> - position 0</li>
	 * <li><strong>data</strong> - position 4</li>
	 * </ol>
	 * 
	 * @return the ByteBuffer representation
	 */
	@Override
	public ByteBuffer getBytes() {
		if(bytes == null) {
			bytes = ByteBuffer.allocate(size());
			bytes.putInt(data.getType().ordinal());
			bytes.put(data.bufferForData());
		}
		return ByteBuffers.asReadOnlyBuffer(bytes);
	}

	/**
	 * Return the java object that is represented by this Value.
	 * 
	 * @return the object representation
	 */
	public Object getObject() {
		return object;
	}

	/**
	 * Return the TObject that is represented by this Value.
	 * 
	 * @return the TObject representation
	 */
	public TObject getTObject() {
		return data;
	}

	/**
	 * Return the {@link Type} that describes the underlying data represented by
	 * this Value.
	 * 
	 * @return the type
	 */
	public Type getType() {
		return data.getType();
	}

	@Override
	public int hashCode() {
		return object.hashCode();
	}

	@Override
	public int size() {
		return CONSTANT_SIZE + data.bufferForData().capacity();
	}

	@Override
	public String toString() {
		return object.toString();
	}

	/**
	 * A {@link Comparator} that is used to sort Values using weak typing.
	 * 
	 * @author jnelson
	 */
	public static final class Sorter implements Comparator<Value> {

		@Override
		public int compare(Value v1, Value v2) {
			Object o1 = v1.getObject();
			Object o2 = v2.getObject();
			if(o1 instanceof Number && o2 instanceof Number) {
				return Numbers.compare((Number) o1, (Number) o2);
			}
			else {
				return o1.toString().compareTo(o2.toString());
			}
		}
	}

}
