/*
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

package org.apache.flink.contrib.streaming.state;

import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.core.memory.ByteArrayOutputStreamWithPos;
import org.apache.flink.core.memory.DataOutputView;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.queryablestate.client.state.serialization.KvStateSerializer;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.state.internal.InternalKvState;
import org.apache.flink.util.Preconditions;

import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteOptions;

import java.io.IOException;

/**
 * Base class for {@link State} implementations that store state in a RocksDB database.
 *
 * <p>State is not stored in this class but in the {@link org.rocksdb.RocksDB} instance that
 * the {@link RocksDBStateBackend} manages and checkpoints.
 *
 * @param <K> The type of the key.
 * @param <N> The type of the namespace.
 * @param <S> The type of {@link State}.
 * @param <SD> The type of {@link StateDescriptor}.
 */
public abstract class AbstractRocksDBState<K, N, S extends State, SD extends StateDescriptor<S, V>, V>
		implements InternalKvState<N>, State {

	/** Serializer for the namespace. */
	final TypeSerializer<N> namespaceSerializer;

	/** The current namespace, which the next value methods will refer to. */
	private N currentNamespace;

	/** Backend that holds the actual RocksDB instance where we store state. */
	protected RocksDBKeyedStateBackend<K> backend;

	/** The column family of this particular instance of state. */
	protected ColumnFamilyHandle columnFamily;

	/** State descriptor from which to create this state instance. */
	protected final SD stateDesc;

	/**
	 * We disable writes to the write-ahead-log here.
	 */
	private final WriteOptions writeOptions;

	protected final ByteArrayOutputStreamWithPos keySerializationStream;
	protected final DataOutputView keySerializationDataOutputView;

	private final boolean ambiguousKeyPossible;

	/**
	 * Creates a new RocksDB backed state.
	 *  @param namespaceSerializer The serializer for the namespace.
	 */
	protected AbstractRocksDBState(
			ColumnFamilyHandle columnFamily,
			TypeSerializer<N> namespaceSerializer,
			SD stateDesc,
			RocksDBKeyedStateBackend<K> backend) {

		this.namespaceSerializer = namespaceSerializer;
		this.backend = backend;

		this.columnFamily = columnFamily;

		writeOptions = new WriteOptions();
		writeOptions.setDisableWAL(true);
		this.stateDesc = Preconditions.checkNotNull(stateDesc, "State Descriptor");

		this.keySerializationStream = new ByteArrayOutputStreamWithPos(128);
		this.keySerializationDataOutputView = new DataOutputViewStreamWrapper(keySerializationStream);
		this.ambiguousKeyPossible = AbstractRocksDBUtils.isAmbiguousKeyPossible(backend.getKeySerializer(), namespaceSerializer);
	}

	// ------------------------------------------------------------------------

	@Override
	public void clear() {
		try {
			writeCurrentKeyWithGroupAndNamespace();
			byte[] key = keySerializationStream.toByteArray();
			backend.db.remove(columnFamily, writeOptions, key);
		} catch (IOException | RocksDBException e) {
			throw new RuntimeException("Error while removing entry from RocksDB", e);
		}
	}

	@Override
	public void setCurrentNamespace(N namespace) {
		this.currentNamespace = Preconditions.checkNotNull(namespace, "Namespace");
	}

	@Override
	@SuppressWarnings("unchecked")
	public byte[] getSerializedValue(byte[] serializedKeyAndNamespace) throws Exception {
		Preconditions.checkNotNull(serializedKeyAndNamespace, "Serialized key and namespace");

		//TODO make KvStateSerializer key-group aware to save this round trip and key-group computation
		Tuple2<K, N> des = KvStateSerializer.<K, N>deserializeKeyAndNamespace(
				serializedKeyAndNamespace,
				backend.getKeySerializer(),
				namespaceSerializer);

		int keyGroup = KeyGroupRangeAssignment.assignToKeyGroup(des.f0, backend.getNumberOfKeyGroups());

		// we cannot reuse the keySerializationStream member since this method
		// is called concurrently to the other ones and it may thus contain garbage
		ByteArrayOutputStreamWithPos tmpKeySerializationStream = new ByteArrayOutputStreamWithPos(128);
		DataOutputViewStreamWrapper tmpKeySerializationDateDataOutputView = new DataOutputViewStreamWrapper(tmpKeySerializationStream);

		writeKeyWithGroupAndNamespace(keyGroup, des.f0, des.f1,
			tmpKeySerializationStream, tmpKeySerializationDateDataOutputView);

		return backend.db.get(columnFamily, tmpKeySerializationStream.toByteArray());
	}

	protected void writeCurrentKeyWithGroupAndNamespace() throws IOException {
		writeKeyWithGroupAndNamespace(
			backend.getCurrentKeyGroupIndex(),
			backend.getCurrentKey(),
			currentNamespace,
			keySerializationStream,
			keySerializationDataOutputView);
	}

	protected void writeKeyWithGroupAndNamespace(
			int keyGroup, K key, N namespace,
			ByteArrayOutputStreamWithPos keySerializationStream,
			DataOutputView keySerializationDataOutputView) throws IOException {

		Preconditions.checkNotNull(key, "No key set. This method should not be called outside of a keyed context.");

		keySerializationStream.reset();
		AbstractRocksDBUtils.writeKeyGroup(keyGroup, backend.getKeyGroupPrefixBytes(), keySerializationDataOutputView);
		AbstractRocksDBUtils.writeKey(key, backend.getKeySerializer(), keySerializationStream, keySerializationDataOutputView, ambiguousKeyPossible);
		AbstractRocksDBUtils.writeNameSpace(namespace, namespaceSerializer, keySerializationStream, keySerializationDataOutputView, ambiguousKeyPossible);
	}

	protected Tuple3<Integer, K, N> readKeyWithGroupAndNamespace(ByteArrayInputStreamWithPos inputStream, DataInputView inputView) throws IOException {
		int keyGroup = AbstractRocksDBUtils.readKeyGroup(backend.getKeyGroupPrefixBytes(), inputView);
		K key = AbstractRocksDBUtils.readKey(backend.getKeySerializer(), inputStream, inputView, ambiguousKeyPossible);
		N namespace = AbstractRocksDBUtils.readNamespace(namespaceSerializer, inputStream, inputView, ambiguousKeyPossible);

		return new Tuple3<>(keyGroup, key, namespace);
	}

	/**
	 * Utils for RocksDB state serialization and deserialization.
 	 */
	static class AbstractRocksDBUtils {

		public static int readKeyGroup(int keyGroupPrefixBytes, DataInputView inputView) throws IOException {
			int keyGroup = 0;
			for (int i = 0; i < keyGroupPrefixBytes; ++i) {
				keyGroup <<= 8;
				keyGroup |= (inputView.readByte() & 0xFF);
			}
			return keyGroup;
		}

		public static <RK> RK readKey(
			TypeSerializer<RK> keySerializer,
			ByteArrayInputStreamWithPos inputStream,
			DataInputView inputView,
			boolean ambiguousKeyPossible) throws IOException {
			int beforeRead = inputStream.getPosition();
			RK key = keySerializer.deserialize(inputView);
			if (ambiguousKeyPossible) {
				int length = inputStream.getPosition() - beforeRead;
				readVariableIntBytes(inputView, length);
			}
			return key;
		}

		public static <RN> RN readNamespace(
			TypeSerializer<RN> namespaceSerializer,
			ByteArrayInputStreamWithPos inputStream,
			DataInputView inputView,
			boolean ambiguousKeyPossible) throws IOException {
			int beforeRead = inputStream.getPosition();
			RN namespace = namespaceSerializer.deserialize(inputView);
			if (ambiguousKeyPossible) {
				int length = inputStream.getPosition() - beforeRead;
				readVariableIntBytes(inputView, length);
			}
			return namespace;
		}

		public static <WN> void writeNameSpace(
			WN namespace,
			TypeSerializer<WN> namespaceSerializer,
			ByteArrayOutputStreamWithPos keySerializationStream,
			DataOutputView keySerializationDataOutputView,
			boolean ambiguousKeyPossible) throws IOException {

<<<<<<< HEAD
	private static void writeVariableIntBytes(
			int value,
			DataOutputView keySerializationDateDataOutputView)
			throws IOException {
		do {
			keySerializationDateDataOutputView.writeByte(value);
			value >>>= 8;
		} while (value != 0);
	}
<<<<<<< HEAD
=======
=======
			int beforeWrite = keySerializationStream.getPosition();
			namespaceSerializer.serialize(namespace, keySerializationDataOutputView);
>>>>>>> 56b67ffefb... Refactor AbstractRocksDBState.

			if (ambiguousKeyPossible) {
				//write length of namespace
				writeLengthFrom(beforeWrite, keySerializationStream,
					keySerializationDataOutputView);
			}
		}

		public static boolean isAmbiguousKeyPossible(TypeSerializer keySerializer, TypeSerializer namespaceSerializer) {
			return (keySerializer.getLength() < 0) && (namespaceSerializer.getLength() < 0);
		}

		public static void writeKeyGroup(
			int keyGroup,
			int keyGroupPrefixBytes,
			DataOutputView keySerializationDateDataOutputView) throws IOException {
			for (int i = keyGroupPrefixBytes; --i >= 0; ) {
				keySerializationDateDataOutputView.writeByte(keyGroup >>> (i << 3));
			}
		}

		public static <WK> void writeKey(
			WK key,
			TypeSerializer<WK> keySerializer,
			ByteArrayOutputStreamWithPos keySerializationStream,
			DataOutputView keySerializationDataOutputView,
			boolean ambiguousKeyPossible) throws IOException {
			//write key
			int beforeWrite = keySerializationStream.getPosition();
			keySerializer.serialize(key, keySerializationDataOutputView);

			if (ambiguousKeyPossible) {
				//write size of key
				writeLengthFrom(beforeWrite, keySerializationStream,
					keySerializationDataOutputView);
			}
		}

		private static void readVariableIntBytes(DataInputView inputView, int value) throws IOException {
			do {
				inputView.readByte();
				value >>>= 8;
			} while (value != 0);
		}

		private static void writeLengthFrom(
			int fromPosition,
			ByteArrayOutputStreamWithPos keySerializationStream,
			DataOutputView keySerializationDateDataOutputView) throws IOException {
			int length = keySerializationStream.getPosition() - fromPosition;
			writeVariableIntBytes(length, keySerializationDateDataOutputView);
		}

		private static void writeVariableIntBytes(
			int value,
			DataOutputView keySerializationDateDataOutputView)
			throws IOException {
			do {
				keySerializationDateDataOutputView.writeByte(value);
				value >>>= 8;
			} while (value != 0);
		}
	}
>>>>>>> 8bc857cf72... add unit test `RocksDBRocksIteratorWrapperTest` and fix the bug when ambiguousKeyPossible is true.
}
