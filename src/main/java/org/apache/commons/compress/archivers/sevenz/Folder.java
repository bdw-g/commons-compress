/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.compress.archivers.sevenz;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

import org.apache.commons.lang3.ArrayUtils;

/**
 * The unit of solid compression.
 */
final class Folder {

    static final Folder[] EMPTY_FOLDER_ARRAY = {};

    /** List of coders used in this folder, for example one for compression, one for encryption. */
    Coder[] coders;

    /**
     * Total number of input streams across all coders. This field is currently unused but technically part of the 7z API.
     */
    long totalInputStreams;

    /** Total number of output streams across all coders. */
    long totalOutputStreams;

    /** Mapping between input and output streams. */
    BindPair[] bindPairs;

    /** Indices of input streams, one per input stream not listed in bindPairs. */
    long[] packedStreams;

    /** Unpack sizes, per each output stream. */
    long[] unpackSizes;

    /** Whether the folder has a CRC. */
    boolean hasCrc;

    /** The CRC, if present. */
    long crc;

    /**
     * The number of unpack substreams, product of the number of output streams and the number of non-empty files in this folder.
     */
    int numUnpackSubStreams;

    int findBindPairForInStream(final int index) {
        if (bindPairs != null) {
            for (int i = 0; i < bindPairs.length; i++) {
                if (bindPairs[i].inIndex == index) {
                    return i;
                }
            }
        }
        return -1;
    }

    int findBindPairForOutStream(final int index) {
        if (bindPairs != null) {
            for (int i = 0; i < bindPairs.length; i++) {
                if (bindPairs[i].outIndex == index) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Sorts Coders using bind pairs.
     * <p>
     * The first coder reads from the packed stream (we currently only support single input stream decoders), the second reads from the output of the first and
     * so on.
     * </p>
     */
    Iterable<Coder> getOrderedCoders() throws IOException {
        if (ArrayUtils.isEmpty(packedStreams) || ArrayUtils.isEmpty(coders)) {
            return Collections.emptyList();
        }
        final LinkedList<Coder> list = new LinkedList<>();
        int current = (int) packedStreams[0]; // more that 2^31 coders?
        while (current >= 0 && current < coders.length) {
            if (list.contains(coders[current])) {
                throw new IOException("folder uses the same coder more than once in coder chain");
            }
            list.addLast(coders[current]);
            final int pair = findBindPairForOutStream(current);
            current = pair != -1 ? (int) bindPairs[pair].inIndex : -1;
        }
        return list;
    }

    long getUnpackSize() {
        if (totalOutputStreams == 0) {
            return 0;
        }
        for (int i = (int) totalOutputStreams - 1; i >= 0; i--) {
            if (findBindPairForOutStream(i) < 0) {
                return unpackSizes[i];
            }
        }
        return 0;
    }

    long getUnpackSizeForCoder(final Coder coder) {
        if (coders != null) {
            for (int i = 0; i < coders.length; i++) {
                if (coders[i] == coder) {
                    return unpackSizes[i];
                }
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return "Folder with " + coders.length + " coders, " + totalInputStreams + " input streams, " + totalOutputStreams + " output streams, "
                + bindPairs.length + " bind pairs, " + packedStreams.length + " packed streams, " + unpackSizes.length + " unpack sizes, "
                + (hasCrc ? "with CRC " + crc : "without CRC") + " and " + numUnpackSubStreams + " unpack streams";
    }
}
