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

/*
 * This package is based on the work done by Timothy Gerard Endres
 * (time@ice.com) to whom the Ant project is very grateful for his great code.
 */

package org.apache.commons.compress.archivers.tar;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipEncoding;
import org.apache.commons.compress.archivers.zip.ZipEncodingHelper;
import org.apache.commons.compress.utils.ArchiveUtils;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;

/**
 * The TarInputStream reads a Unix tar archive as an InputStream. methods are provided to position at each successive entry in the archive, and the read each
 * entry as a normal input stream using read().
 *
 * @NotThreadSafe
 */
public class TarArchiveInputStream extends ArchiveInputStream<TarArchiveEntry> {

    /**
     * IBM AIX <a href=""https://www.ibm.com/docs/sv/aix/7.2.0?topic=files-tarh-file">tar.h</a>: "This field is terminated with a space only."
     */
    private static final String VERSION_AIX = "0 ";

    private static final int SMALL_BUFFER_SIZE = 256;

    /**
     * Checks if the signature matches what is expected for a tar file.
     *
     * @param signature the bytes to check.
     * @param length    the number of bytes to check.
     * @return true, if this stream is a tar archive stream, false otherwise.
     */
    public static boolean matches(final byte[] signature, final int length) {
        final int versionOffset = TarConstants.VERSION_OFFSET;
        final int versionLen = TarConstants.VERSIONLEN;
        if (length < versionOffset + versionLen) {
            return false;
        }
        final int magicOffset = TarConstants.MAGIC_OFFSET;
        final int magicLen = TarConstants.MAGICLEN;
        if (ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_POSIX, signature, magicOffset, magicLen)
                && ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_POSIX, signature, versionOffset, versionLen)) {
            return true;
        }
        // IBM AIX tar.h https://www.ibm.com/docs/sv/aix/7.2.0?topic=files-tarh-file : "This field is terminated with a space only."
        if (ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_POSIX, signature, magicOffset, magicLen)
                && ArchiveUtils.matchAsciiBuffer(VERSION_AIX, signature, versionOffset, versionLen)) {
            return true;
        }
        if (ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_GNU, signature, magicOffset, magicLen)
                && (ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_GNU_SPACE, signature, versionOffset, versionLen)
                        || ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_GNU_ZERO, signature, versionOffset, versionLen))) {
            return true;
        }
        // COMPRESS-107 - recognize Ant tar files
        return ArchiveUtils.matchAsciiBuffer(TarConstants.MAGIC_ANT, signature, magicOffset, magicLen)
                && ArchiveUtils.matchAsciiBuffer(TarConstants.VERSION_ANT, signature, versionOffset, versionLen);
    }

    private final byte[] smallBuf = new byte[SMALL_BUFFER_SIZE];

    /** The buffer to store the TAR header. **/
    private final byte[] recordBuffer;

    /** The size of a block. */
    private final int blockSize;

    /** True if stream is at EOF. */
    private boolean atEof;

    /** Size of the current . */
    private long entrySize;

    /** How far into the entry the stream is at. */
    private long entryOffset;

    /** Input streams for reading sparse entries. **/
    private List<InputStream> sparseInputStreams;

    /** The index of current input stream being read when reading sparse entries. */
    private int currentSparseInputStreamIndex;

    /** The meta-data about the current entry. */
    private TarArchiveEntry currEntry;

    /** The encoding of the file. */
    private final ZipEncoding zipEncoding;

    /** The global PAX header. */
    private Map<String, String> globalPaxHeaders = new HashMap<>();

    /** The global sparse headers, this is only used in PAX Format 0.X. */
    private final List<TarArchiveStructSparse> globalSparseHeaders = new ArrayList<>();

    private final boolean lenient;

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     */
    public TarArchiveInputStream(final InputStream inputStream) {
        this(inputStream, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE);
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param lenient     when set to true illegal values for group/userid, mode, device numbers and timestamp will be ignored and the fields set to
     *                    {@link TarArchiveEntry#UNKNOWN}. When set to false such illegal fields cause an exception instead.
     * @since 1.19
     */
    public TarArchiveInputStream(final InputStream inputStream, final boolean lenient) {
        this(inputStream, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE, null, lenient);
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param blockSize   the block size to use
     */
    public TarArchiveInputStream(final InputStream inputStream, final int blockSize) {
        this(inputStream, blockSize, TarConstants.DEFAULT_RCDSIZE);
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param blockSize   the block size to use
     * @param recordSize  the record size to use
     */
    public TarArchiveInputStream(final InputStream inputStream, final int blockSize, final int recordSize) {
        this(inputStream, blockSize, recordSize, null);
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param blockSize   the block size to use
     * @param recordSize  the record size to use
     * @param encoding    name of the encoding to use for file names
     * @since 1.4
     */
    public TarArchiveInputStream(final InputStream inputStream, final int blockSize, final int recordSize, final String encoding) {
        this(inputStream, blockSize, recordSize, encoding, false);
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param blockSize   the block size to use
     * @param recordSize  the record size to use
     * @param encoding    name of the encoding to use for file names
     * @param lenient     when set to true illegal values for group/userid, mode, device numbers and timestamp will be ignored and the fields set to
     *                    {@link TarArchiveEntry#UNKNOWN}. When set to false such illegal fields cause an exception instead.
     * @since 1.19
     */
    public TarArchiveInputStream(final InputStream inputStream, final int blockSize, final int recordSize, final String encoding, final boolean lenient) {
        super(inputStream, encoding);
        this.zipEncoding = ZipEncodingHelper.getZipEncoding(encoding);
        this.recordBuffer = new byte[recordSize];
        this.blockSize = blockSize;
        this.lenient = lenient;
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param blockSize   the block size to use
     * @param encoding    name of the encoding to use for file names
     * @since 1.4
     */
    public TarArchiveInputStream(final InputStream inputStream, final int blockSize, final String encoding) {
        this(inputStream, blockSize, TarConstants.DEFAULT_RCDSIZE, encoding);
    }

    /**
     * Constructs a new instance.
     *
     * @param inputStream the input stream to use
     * @param encoding    name of the encoding to use for file names
     * @since 1.4
     */
    public TarArchiveInputStream(final InputStream inputStream, final String encoding) {
        this(inputStream, TarConstants.DEFAULT_BLKSIZE, TarConstants.DEFAULT_RCDSIZE, encoding);
    }

    private void applyPaxHeadersToCurrentEntry(final Map<String, String> headers, final List<TarArchiveStructSparse> sparseHeaders) throws IOException {
        currEntry.updateEntryFromPaxHeaders(headers);
        currEntry.setSparseHeaders(sparseHeaders);
    }

    /**
     * Gets the available data that can be read from the current entry in the archive. This does not indicate how much data is left in the entire archive, only
     * in the current entry. This value is determined from the entry's size header field and the amount of data already read from the current entry.
     * Integer.MAX_VALUE is returned in case more than Integer.MAX_VALUE bytes are left in the current entry in the archive.
     *
     * @return The number of available bytes for the current entry.
     * @throws IOException for signature
     */
    @Override
    public int available() throws IOException {
        if (isDirectory()) {
            return 0;
        }
        final long available = currEntry.getRealSize() - entryOffset;
        if (available > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) available;
    }

    /**
     * Build the input streams consisting of all-zero input streams and non-zero input streams. When reading from the non-zero input streams, the data is
     * actually read from the original input stream. The size of each input stream is introduced by the sparse headers.
     * <p>
     * NOTE : Some all-zero input streams and non-zero input streams have the size of 0. We DO NOT store the 0 size input streams because they are meaningless.
     * </p>
     */
    private void buildSparseInputStreams() throws IOException {
        currentSparseInputStreamIndex = -1;
        sparseInputStreams = new ArrayList<>();

        final List<TarArchiveStructSparse> sparseHeaders = currEntry.getOrderedSparseHeaders();

        // Stream doesn't need to be closed at all as it doesn't use any resources
        final InputStream zeroInputStream = new TarArchiveSparseZeroInputStream(); // NOSONAR
        // logical offset into the extracted entry
        long offset = 0;
        for (final TarArchiveStructSparse sparseHeader : sparseHeaders) {
            final long zeroBlockSize = sparseHeader.getOffset() - offset;
            if (zeroBlockSize < 0) {
                // sparse header says to move backwards inside the extracted entry
                throw new IOException("Corrupted struct sparse detected");
            }
            // only store the zero block if it is not empty
            if (zeroBlockSize > 0) {
                // @formatter:off
                sparseInputStreams.add(BoundedInputStream.builder()
                        .setInputStream(zeroInputStream)
                        .setMaxCount(sparseHeader.getOffset() - offset)
                        .get());
                // @formatter:on
            }
            // only store the input streams with non-zero size
            if (sparseHeader.getNumbytes() > 0) {
                // @formatter:off
                sparseInputStreams.add(BoundedInputStream.builder()
                        .setInputStream(in)
                        .setMaxCount(sparseHeader.getNumbytes())
                        .get());
                // @formatter:on
            }
            offset = sparseHeader.getOffset() + sparseHeader.getNumbytes();
        }
        if (!sparseInputStreams.isEmpty()) {
            currentSparseInputStreamIndex = 0;
        }
    }

    /**
     * Tests whether this class is able to read the given entry.
     *
     * @return The implementation will return true if the {@link ArchiveEntry} is an instance of {@link TarArchiveEntry}
     */
    @Override
    public boolean canReadEntryData(final ArchiveEntry archiveEntry) {
        return archiveEntry instanceof TarArchiveEntry;
    }

    /**
     * Closes this stream. Calls the TarBuffer's close() method.
     *
     * @throws IOException on error
     */
    @Override
    public void close() throws IOException {
        // Close all the input streams in sparseInputStreams
        if (sparseInputStreams != null) {
            for (final InputStream inputStream : sparseInputStreams) {
                inputStream.close();
            }
        }
        in.close();
    }

    /**
     * This method is invoked once the end of the archive is hit, it tries to consume the remaining bytes under the assumption that the tool creating this
     * archive has padded the last block.
     */
    private void consumeRemainderOfLastBlock() throws IOException {
        final long bytesReadOfLastBlock = getBytesRead() % blockSize;
        if (bytesReadOfLastBlock > 0) {
            count(IOUtils.skip(in, blockSize - bytesReadOfLastBlock));
        }
    }

    /**
     * For FileInputStream, the skip always return the number you input, so we need the available bytes to determine how many bytes are actually skipped
     *
     * @param available available bytes returned by inputStream.available()
     * @param skipped   skipped bytes returned by inputStream.skip()
     * @param expected  bytes expected to skip
     * @return number of bytes actually skipped
     * @throws IOException if a truncated tar archive is detected
     */
    private long getActuallySkipped(final long available, final long skipped, final long expected) throws IOException {
        long actuallySkipped = skipped;
        if (in instanceof FileInputStream) {
            actuallySkipped = Math.min(skipped, available);
        }
        if (actuallySkipped != expected) {
            throw new IOException("Truncated TAR archive");
        }
        return actuallySkipped;
    }

    /**
     * Gets the current TAR Archive Entry that this input stream is processing
     *
     * @return The current Archive Entry
     */
    public TarArchiveEntry getCurrentEntry() {
        return currEntry;
    }

    /**
     * Gets the next entry in this tar archive as long name data.
     *
     * @return The next entry in the archive as long name data, or null.
     * @throws IOException on error
     */
    protected byte[] getLongNameData() throws IOException {
        // read in the name
        final ByteArrayOutputStream longName = new ByteArrayOutputStream();
        int length = 0;
        while ((length = read(smallBuf)) >= 0) {
            longName.write(smallBuf, 0, length);
        }
        getNextEntry();
        if (currEntry == null) {
            // Bugzilla: 40334
            // Malformed tar file - long entry name not followed by entry
            return null;
        }
        byte[] longNameData = longName.toByteArray();
        // remove trailing null terminator(s)
        length = longNameData.length;
        while (length > 0 && longNameData[length - 1] == 0) {
            --length;
        }
        if (length != longNameData.length) {
            longNameData = Arrays.copyOf(longNameData, length);
        }
        return longNameData;
    }

    /**
     * Gets the next TarArchiveEntry in this stream.
     *
     * @return the next entry, or {@code null} if there are no more entries
     * @throws IOException if the next entry could not be read
     */
    @Override
    public TarArchiveEntry getNextEntry() throws IOException {
        return getNextTarEntry();
    }

    /**
     * Gets the next entry in this tar archive. This will skip over any remaining data in the current entry, if there is one, and place the input stream at the
     * header of the next entry, and read the header and instantiate a new TarEntry from the header bytes and return that entry. If there are no more entries in
     * the archive, null will be returned to indicate that the end of the archive has been reached.
     *
     * @return The next TarEntry in the archive, or null.
     * @throws IOException on error
     * @deprecated Use {@link #getNextEntry()}.
     */
    @Deprecated
    public TarArchiveEntry getNextTarEntry() throws IOException {
        if (isAtEOF()) {
            return null;
        }
        if (currEntry != null) {
            /* Skip will only go to the end of the current entry */
            IOUtils.skip(this, Long.MAX_VALUE);
            /* skip to the end of the last record */
            skipRecordPadding();
        }
        final byte[] headerBuf = getRecord();
        if (headerBuf == null) {
            /* hit EOF */
            currEntry = null;
            return null;
        }
        try {
            currEntry = new TarArchiveEntry(globalPaxHeaders, headerBuf, zipEncoding, lenient);
        } catch (final IllegalArgumentException e) {
            throw new IOException("Error detected parsing the header", e);
        }
        entryOffset = 0;
        entrySize = currEntry.getSize();
        if (currEntry.isGNULongLinkEntry()) {
            final byte[] longLinkData = getLongNameData();
            if (longLinkData == null) {
                // Bugzilla: 40334
                // Malformed tar file - long link entry name not followed by entry
                return null;
            }
            currEntry.setLinkName(zipEncoding.decode(longLinkData));
        }
        if (currEntry.isGNULongNameEntry()) {
            final byte[] longNameData = getLongNameData();
            if (longNameData == null) {
                // Bugzilla: 40334
                // Malformed tar file - long entry name not followed by entry
                return null;
            }
            // COMPRESS-509 : the name of directories should end with '/'
            final String name = zipEncoding.decode(longNameData);
            currEntry.setName(name);
            if (currEntry.isDirectory() && !name.endsWith("/")) {
                currEntry.setName(name + "/");
            }
        }
        if (currEntry.isGlobalPaxHeader()) { // Process Global Pax headers
            readGlobalPaxHeaders();
        }
        try {
            if (currEntry.isPaxHeader()) { // Process Pax headers
                paxHeaders();
            } else if (!globalPaxHeaders.isEmpty()) {
                applyPaxHeadersToCurrentEntry(globalPaxHeaders, globalSparseHeaders);
            }
        } catch (final NumberFormatException e) {
            throw new IOException("Error detected parsing the pax header", e);
        }
        if (currEntry.isOldGNUSparse()) { // Process sparse files
            readOldGNUSparse();
        }
        // If the size of the next element in the archive has changed
        // due to a new size being reported in the POSIX header
        // information, we update entrySize here so that it contains
        // the correct value.
        entrySize = currEntry.getSize();
        return currEntry;
    }

    /**
     * Gets the next record in this tar archive. This will skip over any remaining data in the current entry, if there is one, and place the input stream at the
     * header of the next entry.
     * <p>
     * If there are no more entries in the archive, null will be returned to indicate that the end of the archive has been reached. At the same time the
     * {@code hasHitEOF} marker will be set to true.
     * </p>
     *
     * @return The next header in the archive, or null.
     * @throws IOException on error
     */
    private byte[] getRecord() throws IOException {
        byte[] headerBuf = readRecord();
        setAtEOF(isEOFRecord(headerBuf));
        if (isAtEOF() && headerBuf != null) {
            tryToConsumeSecondEOFRecord();
            consumeRemainderOfLastBlock();
            headerBuf = null;
        }
        return headerBuf;
    }

    /**
     * Gets the record size being used by this stream's buffer.
     *
     * @return The TarBuffer record size.
     */
    public int getRecordSize() {
        return recordBuffer.length;
    }

    /**
     * Tests whether we are at the end-of-file.
     *
     * @return whether we are at the end-of-file.
     */
    protected final boolean isAtEOF() {
        return atEof;
    }

    private boolean isDirectory() {
        return currEntry != null && currEntry.isDirectory();
    }

    /**
     * Tests if an archive record indicate End of Archive. End of archive is indicated by a record that consists entirely of null bytes.
     *
     * @param record The record data to check.
     * @return true if the record data is an End of Archive
     */
    protected boolean isEOFRecord(final byte[] record) {
        return record == null || ArchiveUtils.isArrayZero(record, getRecordSize());
    }

    /**
     * Since we do not support marking just yet, we do nothing.
     *
     * @param markLimit The limit to mark.
     */
    @Override
    public synchronized void mark(final int markLimit) {
    }

    /**
     * Since we do not support marking just yet, we return false.
     *
     * @return false.
     */
    @Override
    public boolean markSupported() {
        return false;
    }

    /**
     * For PAX Format 0.0, the sparse headers(GNU.sparse.offset and GNU.sparse.numbytes) may appear multi times, and they look like:
     * <p>
     * GNU.sparse.size=size GNU.sparse.numblocks=numblocks repeat numblocks times GNU.sparse.offset=offset GNU.sparse.numbytes=numbytes end repeat
     * </p>
     * <p>
     * For PAX Format 0.1, the sparse headers are stored in a single variable : GNU.sparse.map
     * </p>
     * <p>
     * GNU.sparse.map Map of non-null data chunks. It is a string consisting of comma-separated values "offset,size[,offset-1,size-1...]"
     * </p>
     * <p>
     * For PAX Format 1.X: The sparse map itself is stored in the file data block, preceding the actual file data. It consists of a series of decimal numbers
     * delimited by newlines. The map is padded with nulls to the nearest block boundary. The first number gives the number of entries in the map. Following are
     * map entries, each one consisting of two numbers giving the offset and size of the data block it describes.
     * </p>
     *
     * @throws IOException if an I/O error occurs.
     */
    private void paxHeaders() throws IOException {
        List<TarArchiveStructSparse> sparseHeaders = new ArrayList<>();
        final Map<String, String> headers = TarUtils.parsePaxHeaders(this, sparseHeaders, globalPaxHeaders, entrySize);
        // for 0.1 PAX Headers
        if (headers.containsKey(TarGnuSparseKeys.MAP)) {
            sparseHeaders = new ArrayList<>(TarUtils.parseFromPAX01SparseHeaders(headers.get(TarGnuSparseKeys.MAP)));
        }
        getNextEntry(); // Get the actual file entry
        if (currEntry == null) {
            throw new IOException("premature end of tar archive. Didn't find any entry after PAX header.");
        }
        applyPaxHeadersToCurrentEntry(headers, sparseHeaders);
        // for 1.0 PAX Format, the sparse map is stored in the file data block
        if (currEntry.isPaxGNU1XSparse()) {
            sparseHeaders = TarUtils.parsePAX1XSparseHeaders(in, getRecordSize());
            currEntry.setSparseHeaders(sparseHeaders);
        }
        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams();
    }

    /**
     * Reads bytes from the current tar archive entry.
     * <p>
     * This method is aware of the boundaries of the current entry in the archive and will deal with them as if they were this stream's start and EOF.
     * </p>
     *
     * @param buf       The buffer into which to place bytes read.
     * @param offset    The offset at which to place bytes read.
     * @param numToRead The number of bytes to read.
     * @return The number of bytes read, or -1 at EOF.
     * @throws IOException on error
     */
    @Override
    public int read(final byte[] buf, final int offset, int numToRead) throws IOException {
        if (numToRead == 0) {
            return 0;
        }
        int totalRead = 0;
        if (isAtEOF() || isDirectory()) {
            return -1;
        }
        if (currEntry == null) {
            throw new IllegalStateException("No current tar entry");
        }
        if (entryOffset >= currEntry.getRealSize()) {
            return -1;
        }
        numToRead = Math.min(numToRead, available());
        if (currEntry.isSparse()) {
            // for sparse entries, we need to read them in another way
            totalRead = readSparse(buf, offset, numToRead);
        } else {
            totalRead = in.read(buf, offset, numToRead);
        }
        if (totalRead == -1) {
            if (numToRead > 0) {
                throw new IOException("Truncated TAR archive");
            }
            setAtEOF(true);
        } else {
            count(totalRead);
            entryOffset += totalRead;
        }
        return totalRead;
    }

    private void readGlobalPaxHeaders() throws IOException {
        globalPaxHeaders = TarUtils.parsePaxHeaders(this, globalSparseHeaders, globalPaxHeaders, entrySize);
        getNextEntry(); // Get the actual file entry
        if (currEntry == null) {
            throw new IOException("Error detected parsing the pax header");
        }
    }

    /**
     * Adds the sparse chunks from the current entry to the sparse chunks, including any additional sparse entries following the current entry.
     *
     * @throws IOException on error
     */
    private void readOldGNUSparse() throws IOException {
        if (currEntry.isExtended()) {
            TarArchiveSparseEntry entry;
            do {
                final byte[] headerBuf = getRecord();
                if (headerBuf == null) {
                    throw new IOException("premature end of tar archive. Didn't find extended_header after header with extended flag.");
                }
                entry = new TarArchiveSparseEntry(headerBuf);
                currEntry.getSparseHeaders().addAll(entry.getSparseHeaders());
            } while (entry.isExtended());
        }
        // sparse headers are all done reading, we need to build
        // sparse input streams using these sparse headers
        buildSparseInputStreams();
    }

    /**
     * Reads a record from the input stream and return the data.
     *
     * @return The record data or null if EOF has been hit.
     * @throws IOException on error
     */
    protected byte[] readRecord() throws IOException {
        final int readCount = IOUtils.readFully(in, recordBuffer);
        count(readCount);
        if (readCount != getRecordSize()) {
            return null;
        }
        return recordBuffer;
    }

    /**
     * For sparse tar entries, there are many "holes"(consisting of all 0) in the file. Only the non-zero data is stored in tar files, and they are stored
     * separately. The structure of non-zero data is introduced by the sparse headers using the offset, where a block of non-zero data starts, and numbytes, the
     * length of the non-zero data block. When reading sparse entries, the actual data is read out with "holes" and non-zero data combined together according to
     * the sparse headers.
     *
     * @param buf       The buffer into which to place bytes read.
     * @param offset    The offset at which to place bytes read.
     * @param numToRead The number of bytes to read.
     * @return The number of bytes read, or -1 at EOF.
     * @throws IOException on error
     */
    private int readSparse(final byte[] buf, final int offset, final int numToRead) throws IOException {
        // if there are no actual input streams, just read from the original input stream
        if (sparseInputStreams == null || sparseInputStreams.isEmpty()) {
            return in.read(buf, offset, numToRead);
        }
        if (currentSparseInputStreamIndex >= sparseInputStreams.size()) {
            return -1;
        }
        final InputStream currentInputStream = sparseInputStreams.get(currentSparseInputStreamIndex);
        final int readLen = currentInputStream.read(buf, offset, numToRead);
        // if the current input stream is the last input stream,
        // just return the number of bytes read from current input stream
        if (currentSparseInputStreamIndex == sparseInputStreams.size() - 1) {
            return readLen;
        }
        // if EOF of current input stream is meet, open a new input stream and recursively call read
        if (readLen == -1) {
            currentSparseInputStreamIndex++;
            return readSparse(buf, offset, numToRead);
        }
        // if the rest data of current input stream is not long enough, open a new input stream
        // and recursively call read
        if (readLen < numToRead) {
            currentSparseInputStreamIndex++;
            final int readLenOfNext = readSparse(buf, offset + readLen, numToRead - readLen);
            if (readLenOfNext == -1) {
                return readLen;
            }
            return readLen + readLenOfNext;
        }
        // if the rest data of current input stream is enough(which means readLen == len), just return readLen
        return readLen;
    }

    /**
     * Since we do not support marking just yet, we do nothing.
     */
    @Override
    public synchronized void reset() {
        // empty
    }

    /**
     * Sets whether we are at the end-of-file.
     *
     * @param atEof whether we are at the end-of-file.
     */
    protected final void setAtEOF(final boolean atEof) {
        this.atEof = atEof;
    }

    /**
     * Sets the current entry.
     *
     * @param currEntry the current entry.
     */
    protected final void setCurrentEntry(final TarArchiveEntry currEntry) {
        this.currEntry = currEntry;
    }

    /**
     * Skips over and discards {@code n} bytes of data from this input stream. The {@code skip} method may, for a variety of reasons, end up skipping over some
     * smaller number of bytes, possibly {@code 0}. This may result from any of a number of conditions; reaching end of file or end of entry before {@code n}
     * bytes have been skipped; are only two possibilities. The actual number of bytes skipped is returned. If {@code n} is negative, no bytes are skipped.
     *
     * @param n the number of bytes to be skipped.
     * @return the actual number of bytes skipped.
     * @throws IOException if a truncated tar archive is detected or some other I/O error occurs
     */
    @Override
    public long skip(final long n) throws IOException {
        if (n <= 0 || isDirectory()) {
            return 0;
        }
        final long availableOfInputStream = in.available();
        final long available = currEntry.getRealSize() - entryOffset;
        final long numToSkip = Math.min(n, available);
        long skipped;
        if (!currEntry.isSparse()) {
            skipped = IOUtils.skip(in, numToSkip);
            // for non-sparse entry, we should get the bytes actually skipped bytes along with
            // inputStream.available() if inputStream is instance of FileInputStream
            skipped = getActuallySkipped(availableOfInputStream, skipped, numToSkip);
        } else {
            skipped = skipSparse(numToSkip);
        }
        count(skipped);
        entryOffset += skipped;
        return skipped;
    }

    /**
     * The last record block should be written at the full size, so skip any additional space used to fill a record after an entry.
     *
     * @throws IOException if a truncated tar archive is detected
     */
    private void skipRecordPadding() throws IOException {
        if (!isDirectory() && this.entrySize > 0 && this.entrySize % getRecordSize() != 0) {
            final long available = in.available();
            final long numRecords = this.entrySize / getRecordSize() + 1;
            final long padding = numRecords * getRecordSize() - this.entrySize;
            long skipped = IOUtils.skip(in, padding);
            skipped = getActuallySkipped(available, skipped, padding);
            count(skipped);
        }
    }

    /**
     * Skip n bytes from current input stream, if the current input stream doesn't have enough data to skip, jump to the next input stream and skip the rest
     * bytes, keep doing this until total n bytes are skipped or the input streams are all skipped
     *
     * @param n bytes of data to skip
     * @return actual bytes of data skipped
     * @throws IOException if an I/O error occurs.
     */
    private long skipSparse(final long n) throws IOException {
        if (sparseInputStreams == null || sparseInputStreams.isEmpty()) {
            return in.skip(n);
        }
        long bytesSkipped = 0;
        while (bytesSkipped < n && currentSparseInputStreamIndex < sparseInputStreams.size()) {
            final InputStream currentInputStream = sparseInputStreams.get(currentSparseInputStreamIndex);
            bytesSkipped += currentInputStream.skip(n - bytesSkipped);
            if (bytesSkipped < n) {
                currentSparseInputStreamIndex++;
            }
        }
        return bytesSkipped;
    }

    /**
     * Tries to read the next record rewinding the stream if it is not an EOF record.
     * <p>
     * This is meant to protect against cases where a tar implementation has written only one EOF record when two are expected. Actually this won't help since a
     * non-conforming implementation likely won't fill full blocks consisting of - by default - ten records either so we probably have already read beyond the
     * archive anyway.
     * </p>
     */
    private void tryToConsumeSecondEOFRecord() throws IOException {
        boolean shouldReset = true;
        final boolean marked = in.markSupported();
        if (marked) {
            in.mark(getRecordSize());
        }
        try {
            shouldReset = !isEOFRecord(readRecord());
        } finally {
            if (shouldReset && marked) {
                pushedBackBytes(getRecordSize());
                in.reset();
            }
        }
    }
}
