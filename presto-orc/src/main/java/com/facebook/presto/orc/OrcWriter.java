/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc;

import com.facebook.presto.common.Page;
import com.facebook.presto.common.io.DataOutput;
import com.facebook.presto.common.io.DataSink;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.orc.OrcWriteValidation.OrcWriteValidationBuilder;
import com.facebook.presto.orc.OrcWriteValidation.OrcWriteValidationMode;
import com.facebook.presto.orc.metadata.ColumnEncoding;
import com.facebook.presto.orc.metadata.CompressedMetadataWriter;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.metadata.DwrfEncryption;
import com.facebook.presto.orc.metadata.DwrfStripeCacheData;
import com.facebook.presto.orc.metadata.DwrfStripeCacheWriter;
import com.facebook.presto.orc.metadata.EncryptionGroup;
import com.facebook.presto.orc.metadata.Footer;
import com.facebook.presto.orc.metadata.Metadata;
import com.facebook.presto.orc.metadata.OrcType;
import com.facebook.presto.orc.metadata.Stream;
import com.facebook.presto.orc.metadata.StripeEncryptionGroup;
import com.facebook.presto.orc.metadata.StripeFooter;
import com.facebook.presto.orc.metadata.StripeInformation;
import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.orc.metadata.statistics.StripeStatistics;
import com.facebook.presto.orc.proto.DwrfProto;
import com.facebook.presto.orc.stream.StreamDataOutput;
import com.facebook.presto.orc.writer.ColumnWriter;
import com.facebook.presto.orc.writer.CompressionBufferPool;
import com.facebook.presto.orc.writer.CompressionBufferPool.LastUsedCompressionBufferPool;
import com.facebook.presto.orc.writer.DictionaryColumnWriter;
import com.facebook.presto.orc.writer.StreamLayout;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.airlift.units.DataSize;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.joda.time.DateTimeZone;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.facebook.presto.common.io.DataOutput.createDataOutput;
import static com.facebook.presto.orc.DwrfEncryptionInfo.UNENCRYPTED;
import static com.facebook.presto.orc.DwrfEncryptionInfo.createNodeToGroupMap;
import static com.facebook.presto.orc.FlushReason.CLOSED;
import static com.facebook.presto.orc.OrcEncoding.DWRF;
import static com.facebook.presto.orc.OrcReader.validateFile;
import static com.facebook.presto.orc.metadata.ColumnEncoding.ColumnEncodingKind.DIRECT;
import static com.facebook.presto.orc.metadata.ColumnEncoding.DEFAULT_SEQUENCE_ID;
import static com.facebook.presto.orc.metadata.DwrfMetadataWriter.toFileStatistics;
import static com.facebook.presto.orc.metadata.DwrfMetadataWriter.toStripeEncryptionGroup;
import static com.facebook.presto.orc.metadata.OrcType.createNodeIdToColumnMap;
import static com.facebook.presto.orc.metadata.OrcType.mapColumnToNode;
import static com.facebook.presto.orc.metadata.PostScript.MAGIC;
import static com.facebook.presto.orc.metadata.statistics.ColumnStatistics.mergeColumnStatistics;
import static com.facebook.presto.orc.writer.ColumnWriters.createColumnWriter;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.lang.Integer.min;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class OrcWriter
        implements Closeable
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(OrcWriter.class).instanceSize();

    static final String PRESTO_ORC_WRITER_VERSION_METADATA_KEY = "presto.writer.version";
    static final String PRESTO_ORC_WRITER_VERSION;

    static {
        String version = OrcWriter.class.getPackage().getImplementationVersion();
        PRESTO_ORC_WRITER_VERSION = version == null ? "UNKNOWN" : version;
    }

    private final WriterStats stats;
    private final OrcWriterFlushPolicy flushPolicy;
    private final DataSink dataSink;
    private final List<Type> types;
    private final OrcEncoding orcEncoding;
    private final ColumnWriterOptions columnWriterOptions;
    private final int rowGroupMaxRowCount;
    private final StreamLayout streamLayout;
    private final Map<String, String> userMetadata;
    private final CompressedMetadataWriter metadataWriter;
    private final DateTimeZone hiveStorageTimeZone;

    private final DwrfEncryptionProvider dwrfEncryptionProvider;
    private final DwrfEncryptionInfo dwrfEncryptionInfo;
    private final Optional<DwrfWriterEncryption> dwrfWriterEncryption;

    private final List<ClosedStripe> closedStripes = new ArrayList<>();
    private final List<OrcType> orcTypes;

    private final List<ColumnWriter> columnWriters;
    private final Optional<DwrfStripeCacheWriter> dwrfStripeCacheWriter;
    private final int dictionaryMaxMemoryBytes;
    private final DictionaryCompressionOptimizer dictionaryCompressionOptimizer;
    @Nullable
    private final OrcWriteValidation.OrcWriteValidationBuilder validationBuilder;
    private final CompressionBufferPool compressionBufferPool;

    private int stripeRowCount;
    private int rowGroupRowCount;
    private int bufferedBytes;
    private long columnWritersRetainedBytes;
    private long closedStripesRetainedBytes;
    private long previouslyRecordedSizeInBytes;
    private boolean closed;

    private long numberOfRows;
    private long stripeRawSize;
    private long rawSize;
    private List<ColumnStatistics> unencryptedStats;
    private final Map<Integer, Integer> nodeIdToColumn;
    private final StreamSizeHelper streamSizeHelper;

    public OrcWriter(
            DataSink dataSink,
            List<String> columnNames,
            List<Type> types,
            OrcEncoding orcEncoding,
            CompressionKind compressionKind,
            Optional<DwrfWriterEncryption> encryption,
            DwrfEncryptionProvider dwrfEncryptionProvider,
            OrcWriterOptions options,
            Map<String, String> userMetadata,
            DateTimeZone hiveStorageTimeZone,
            boolean validate,
            OrcWriteValidationMode validationMode,
            WriterStats stats)
    {
        this(
                dataSink,
                columnNames,
                types,
                Optional.empty(),
                orcEncoding,
                compressionKind,
                encryption,
                dwrfEncryptionProvider,
                options,
                userMetadata,
                hiveStorageTimeZone,
                validate,
                validationMode,
                stats);
    }

    public OrcWriter(
            DataSink dataSink,
            List<String> columnNames,
            List<Type> types,
            Optional<List<OrcType>> inputOrcTypes,
            OrcEncoding orcEncoding,
            CompressionKind compressionKind,
            Optional<DwrfWriterEncryption> encryption,
            DwrfEncryptionProvider dwrfEncryptionProvider,
            OrcWriterOptions options,
            Map<String, String> userMetadata,
            DateTimeZone hiveStorageTimeZone,
            boolean validate,
            OrcWriteValidationMode validationMode,
            WriterStats stats)
    {
        this.validationBuilder = validate ? new OrcWriteValidation.OrcWriteValidationBuilder(validationMode, types).setStringStatisticsLimitInBytes(toIntExact(options.getMaxStringStatisticsLimit().toBytes())) : null;

        this.dataSink = requireNonNull(dataSink, "dataSink is null");
        this.types = ImmutableList.copyOf(requireNonNull(types, "types is null"));
        this.orcEncoding = requireNonNull(orcEncoding, "orcEncoding is null");
        this.compressionBufferPool = new LastUsedCompressionBufferPool();

        requireNonNull(columnNames, "columnNames is null");
        requireNonNull(inputOrcTypes, "inputOrcTypes is null");
        this.orcTypes = inputOrcTypes.orElseGet(() -> OrcType.createOrcRowType(0, columnNames, types));
        this.nodeIdToColumn = createNodeIdToColumnMap(this.orcTypes);

        requireNonNull(compressionKind, "compressionKind is null");
        Set<Integer> flattenedNodes = mapColumnToNode(options.getFlattenedColumns(), orcTypes);
        this.columnWriterOptions = ColumnWriterOptions.builder()
                .setCompressionKind(compressionKind)
                .setCompressionLevel(options.getCompressionLevel())
                .setCompressionMaxBufferSize(options.getMaxCompressionBufferSize())
                .setMinOutputBufferChunkSize(options.getMinOutputBufferChunkSize())
                .setMaxOutputBufferChunkSize(options.getMaxOutputBufferChunkSize())
                .setStringStatisticsLimit(options.getMaxStringStatisticsLimit())
                .setIntegerDictionaryEncodingEnabled(options.isIntegerDictionaryEncodingEnabled())
                .setStringDictionarySortingEnabled(options.isStringDictionarySortingEnabled())
                .setStringDictionaryEncodingEnabled(options.isStringDictionaryEncodingEnabled())
                .setIgnoreDictionaryRowGroupSizes(options.isIgnoreDictionaryRowGroupSizes())
                .setPreserveDirectEncodingStripeCount(options.getPreserveDirectEncodingStripeCount())
                .setCompressionBufferPool(compressionBufferPool)
                .setFlattenedNodes(flattenedNodes)
                .setMapStatisticsEnabled(options.isMapStatisticsEnabled())
                .setMaxFlattenedMapKeyCount(options.getMaxFlattenedMapKeyCount())
                .setResetOutputBuffer(options.isResetOutputBuffer())
                .setLazyOutputBuffer(options.isLazyOutputBuffer())
                .build();
        recordValidation(validation -> validation.setCompression(compressionKind));
        recordValidation(validation -> validation.setFlattenedNodes(flattenedNodes));
        recordValidation(validation -> validation.setOrcTypes(orcTypes));

        requireNonNull(options, "options is null");
        this.flushPolicy = requireNonNull(options.getFlushPolicy(), "flushPolicy is null");
        this.rowGroupMaxRowCount = options.getRowGroupMaxRowCount();
        recordValidation(validation -> validation.setRowGroupMaxRowCount(rowGroupMaxRowCount));
        this.streamLayout = requireNonNull(options.getStreamLayoutFactory().create(), "streamLayout is null");

        this.userMetadata = ImmutableMap.<String, String>builder()
                .putAll(requireNonNull(userMetadata, "userMetadata is null"))
                .put(PRESTO_ORC_WRITER_VERSION_METADATA_KEY, PRESTO_ORC_WRITER_VERSION)
                .build();
        this.metadataWriter = new CompressedMetadataWriter(orcEncoding.createMetadataWriter(), columnWriterOptions, Optional.empty());
        this.hiveStorageTimeZone = requireNonNull(hiveStorageTimeZone, "hiveStorageTimeZone is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.streamSizeHelper = new StreamSizeHelper(orcTypes, columnWriterOptions.getFlattenedNodes(), columnWriterOptions.isMapStatisticsEnabled());

        recordValidation(validation -> validation.setColumnNames(columnNames));

        dwrfWriterEncryption = requireNonNull(encryption, "encryption is null");
        this.dwrfEncryptionProvider = requireNonNull(dwrfEncryptionProvider, "dwrfEncryptionProvider is null");
        if (dwrfWriterEncryption.isPresent()) {
            List<WriterEncryptionGroup> writerEncryptionGroups = dwrfWriterEncryption.get().getWriterEncryptionGroups();
            Map<Integer, Integer> nodeToGroupMap = createNodeToGroupMap(
                    writerEncryptionGroups
                            .stream()
                            .map(WriterEncryptionGroup::getNodes)
                            .collect(toImmutableList()),
                    orcTypes);
            EncryptionLibrary encryptionLibrary = dwrfEncryptionProvider.getEncryptionLibrary(dwrfWriterEncryption.get().getKeyProvider());
            List<byte[]> dataEncryptionKeys = writerEncryptionGroups.stream()
                    .map(group -> encryptionLibrary.generateDataEncryptionKey(group.getIntermediateKeyMetadata().getBytes()))
                    .collect(toImmutableList());
            Map<Integer, DwrfDataEncryptor> dwrfEncryptors = IntStream.range(0, writerEncryptionGroups.size())
                    .boxed()
                    .collect(toImmutableMap(
                            groupId -> groupId,
                            groupId -> new DwrfDataEncryptor(dataEncryptionKeys.get(groupId), encryptionLibrary)));

            List<byte[]> encryptedKeyMetadatas = IntStream.range(0, writerEncryptionGroups.size())
                    .boxed()
                    .map(groupId -> encryptionLibrary.encryptKey(
                            writerEncryptionGroups.get(groupId).getIntermediateKeyMetadata().getBytes(),
                            dataEncryptionKeys.get(groupId),
                            0,
                            dataEncryptionKeys.get(groupId).length))
                    .collect(toImmutableList());
            this.dwrfEncryptionInfo = new DwrfEncryptionInfo(dwrfEncryptors, encryptedKeyMetadatas, nodeToGroupMap);
        }
        else {
            this.dwrfEncryptionInfo = UNENCRYPTED;
        }

        // set DwrfStripeCacheWriter for DWRF files if it's enabled through the options
        if (orcEncoding == DWRF) {
            this.dwrfStripeCacheWriter = options.getDwrfStripeCacheOptions()
                    .map(dwrfWriterOptions -> new DwrfStripeCacheWriter(
                            dwrfWriterOptions.getStripeCacheMode(),
                            dwrfWriterOptions.getStripeCacheMaxSize()));
        }
        else {
            this.dwrfStripeCacheWriter = Optional.empty();
        }

        // create column writers
        OrcType rootType = orcTypes.get(0);
        checkArgument(rootType.getFieldCount() == types.size());
        ImmutableList.Builder<ColumnWriter> columnWriters = ImmutableList.builder();
        ImmutableSet.Builder<DictionaryColumnWriter> dictionaryColumnWriters = ImmutableSet.builder();
        for (int columnIndex = 0; columnIndex < types.size(); columnIndex++) {
            int nodeIndex = rootType.getFieldTypeIndex(columnIndex);
            Type fieldType = types.get(columnIndex);
            ColumnWriter columnWriter = createColumnWriter(
                    nodeIndex,
                    DEFAULT_SEQUENCE_ID,
                    orcTypes,
                    fieldType,
                    columnWriterOptions,
                    orcEncoding,
                    hiveStorageTimeZone,
                    dwrfEncryptionInfo,
                    orcEncoding.createMetadataWriter());
            columnWriters.add(columnWriter);

            if (columnWriter instanceof DictionaryColumnWriter) {
                dictionaryColumnWriters.add((DictionaryColumnWriter) columnWriter);
            }
            else {
                for (ColumnWriter nestedColumnWriter : columnWriter.getNestedColumnWriters()) {
                    if (nestedColumnWriter instanceof DictionaryColumnWriter) {
                        dictionaryColumnWriters.add((DictionaryColumnWriter) nestedColumnWriter);
                    }
                }
            }
        }
        this.columnWriters = columnWriters.build();
        this.dictionaryMaxMemoryBytes = toIntExact(options.getDictionaryMaxMemory().toBytes());
        int dictionaryMemoryAlmostFullRangeBytes = toIntExact(options.getDictionaryMemoryAlmostFullRange().toBytes());
        int dictionaryUsefulCheckColumnSizeBytes = toIntExact(options.getDictionaryUsefulCheckColumnSize().toBytes());
        this.dictionaryCompressionOptimizer = new DictionaryCompressionOptimizer(
                dictionaryColumnWriters.build(),
                flushPolicy.getStripeMinBytes(),
                flushPolicy.getStripeMaxBytes(),
                flushPolicy.getStripeMaxRowCount(),
                dictionaryMaxMemoryBytes,
                dictionaryMemoryAlmostFullRangeBytes,
                dictionaryUsefulCheckColumnSizeBytes,
                options.getDictionaryUsefulCheckPerChunkFrequency());

        for (Entry<String, String> entry : this.userMetadata.entrySet()) {
            recordValidation(validation -> validation.addMetadataProperty(entry.getKey(), utf8Slice(entry.getValue())));
        }

        this.previouslyRecordedSizeInBytes = getRetainedBytes();
        stats.updateSizeInBytes(previouslyRecordedSizeInBytes);
    }

    @VisibleForTesting
    List<ColumnWriter> getColumnWriters()
    {
        return columnWriters;
    }

    @VisibleForTesting
    DictionaryCompressionOptimizer getDictionaryCompressionOptimizer()
    {
        return dictionaryCompressionOptimizer;
    }

    /**
     * Number of bytes already flushed to the data sink.
     */
    public long getWrittenBytes()
    {
        return dataSink.size();
    }

    /**
     * Number of pending bytes not yet flushed.
     */
    public int getBufferedBytes()
    {
        return bufferedBytes;
    }

    public long getRetainedBytes()
    {
        return INSTANCE_SIZE +
                columnWritersRetainedBytes +
                closedStripesRetainedBytes +
                dataSink.getRetainedSizeInBytes() +
                compressionBufferPool.getRetainedBytes() +
                (validationBuilder == null ? 0 : validationBuilder.getRetainedSize());
    }

    public void write(Page page)
            throws IOException
    {
        requireNonNull(page, "page is null");
        if (page.getPositionCount() == 0) {
            return;
        }

        checkArgument(page.getChannelCount() == columnWriters.size());

        if (validationBuilder != null) {
            validationBuilder.addPage(page);
        }

        int maxChunkRowCount = flushPolicy.getMaxChunkRowCount(page);

        while (page != null) {
            // logical size and row group boundaries
            int chunkRows = min(maxChunkRowCount, min(rowGroupMaxRowCount - rowGroupRowCount, flushPolicy.getStripeMaxRowCount() - stripeRowCount));

            // align page to max size per chunk
            chunkRows = min(page.getPositionCount(), chunkRows);

            Page chunk = page.getRegion(0, chunkRows);

            if (chunkRows < page.getPositionCount()) {
                page = page.getRegion(chunkRows, page.getPositionCount() - chunkRows);
            }
            else {
                page = null;
            }
            writeChunk(chunk);
        }

        long recordedSizeInBytes = getRetainedBytes();
        stats.updateSizeInBytes(recordedSizeInBytes - previouslyRecordedSizeInBytes);
        previouslyRecordedSizeInBytes = recordedSizeInBytes;
    }

    private void writeChunk(Page chunk)
            throws IOException
    {
        if (rowGroupRowCount == 0) {
            columnWriters.forEach(ColumnWriter::beginRowGroup);
        }

        // write chunks
        bufferedBytes = 0;
        for (int channel = 0; channel < chunk.getChannelCount(); channel++) {
            ColumnWriter writer = columnWriters.get(channel);
            stripeRawSize += writer.writeBlock(chunk.getBlock(channel));
            bufferedBytes += writer.getBufferedBytes();
        }

        // update stats
        rowGroupRowCount += chunk.getPositionCount();
        checkState(rowGroupRowCount <= rowGroupMaxRowCount);
        stripeRowCount += chunk.getPositionCount();

        // record checkpoint if necessary
        if (rowGroupRowCount == rowGroupMaxRowCount) {
            finishRowGroup();
        }

        // convert dictionary encoded columns to direct if dictionary memory usage exceeded
        dictionaryCompressionOptimizer.optimize(bufferedBytes, stripeRowCount);

        // flush stripe if necessary
        bufferedBytes = toIntExact(columnWriters.stream().mapToLong(ColumnWriter::getBufferedBytes).sum());
        boolean dictionaryIsFull = dictionaryCompressionOptimizer.isFull(bufferedBytes);
        Optional<FlushReason> flushReason = flushPolicy.shouldFlushStripe(stripeRowCount, bufferedBytes, dictionaryIsFull);
        if (flushReason.isPresent()) {
            flushStripe(flushReason.get());
        }
        columnWritersRetainedBytes = columnWriters.stream().mapToLong(ColumnWriter::getRetainedBytes).sum();
    }

    private void finishRowGroup()
    {
        Map<Integer, ColumnStatistics> columnStatistics = new HashMap<>();
        columnWriters.forEach(columnWriter -> columnStatistics.putAll(columnWriter.finishRowGroup()));
        recordValidation(validation -> validation.addRowGroupStatistics(columnStatistics));
        rowGroupRowCount = 0;
    }

    private void flushStripe(FlushReason flushReason)
            throws IOException
    {
        List<DataOutput> outputData = new ArrayList<>();
        long stripeStartOffset = dataSink.size();
        // add header to first stripe (this is not required but nice to have)
        if (closedStripes.isEmpty()) {
            outputData.add(createDataOutput(MAGIC));
            stripeStartOffset += MAGIC.length();
        }

        flushColumnWriters(flushReason);
        try {
            // add stripe data
            outputData.addAll(bufferStripeData(stripeStartOffset, flushReason));
            rawSize += stripeRawSize;
            // if the file is being closed, add the file footer
            if (flushReason == CLOSED) {
                outputData.addAll(bufferFileFooter());
            }

            // write all data
            dataSink.write(outputData);
        }
        finally {
            // open next stripe
            columnWriters.forEach(ColumnWriter::reset);
            dictionaryCompressionOptimizer.reset();
            rowGroupRowCount = 0;
            stripeRowCount = 0;
            stripeRawSize = 0;
            bufferedBytes = toIntExact(columnWriters.stream().mapToLong(ColumnWriter::getBufferedBytes).sum());
        }
    }

    private void flushColumnWriters(FlushReason flushReason)
    {
        if (stripeRowCount == 0) {
            verify(flushReason == CLOSED, "An empty stripe is not allowed");
        }
        else {
            if (rowGroupRowCount > 0) {
                finishRowGroup();
            }

            // convert any dictionary encoded column with a low compression ratio to direct
            dictionaryCompressionOptimizer.finalOptimize(bufferedBytes);
        }

        columnWriters.forEach(ColumnWriter::close);
    }

    /**
     * Collect the data for the stripe.  This is not the actual data, but
     * instead are functions that know how to write the data.
     */
    private List<DataOutput> bufferStripeData(long stripeStartOffset, FlushReason flushReason)
            throws IOException
    {
        if (stripeRowCount == 0) {
            return ImmutableList.of();
        }

        List<Stream> unencryptedStreams = new ArrayList<>(columnWriters.size() * 3);
        Multimap<Integer, Stream> encryptedStreams = ArrayListMultimap.create();
        List<StreamDataOutput> indexStreams = new ArrayList<>(columnWriters.size());

        // get index streams
        long indexLength = 0;
        long offset = 0;
        int previousEncryptionGroup = -1;
        for (ColumnWriter columnWriter : columnWriters) {
            List<StreamDataOutput> streams = columnWriter.getIndexStreams(Optional.empty());
            indexStreams.addAll(streams);
            for (StreamDataOutput indexStream : streams) {
                // The ordering is critical because the stream only contain a length with no offset.
                // if the previous stream was part of a different encryption group, need to specify an offset so we know the column order
                Optional<Integer> encryptionGroup = dwrfEncryptionInfo.getGroupByNodeId(indexStream.getStream().getColumn());
                if (encryptionGroup.isPresent()) {
                    Stream stream = previousEncryptionGroup == encryptionGroup.get() ? indexStream.getStream() : indexStream.getStream().withOffset(offset);
                    encryptedStreams.put(encryptionGroup.get(), stream);
                    previousEncryptionGroup = encryptionGroup.get();
                }
                else {
                    Stream stream = previousEncryptionGroup == -1 ? indexStream.getStream() : indexStream.getStream().withOffset(offset);
                    unencryptedStreams.add(stream);
                    previousEncryptionGroup = -1;
                }
                offset += indexStream.size();
                indexLength += indexStream.size();
            }
        }

        if (dwrfStripeCacheWriter.isPresent()) {
            dwrfStripeCacheWriter.get().addIndexStreams(ImmutableList.copyOf(indexStreams), indexLength);
        }

        // data streams (sorted by size)
        long dataLength = 0;
        List<StreamDataOutput> dataStreams = new ArrayList<>(columnWriters.size() * 2);
        for (ColumnWriter columnWriter : columnWriters) {
            List<StreamDataOutput> streams = columnWriter.getDataStreams();
            dataStreams.addAll(streams);
            dataLength += streams.stream()
                    .mapToLong(StreamDataOutput::size)
                    .sum();
        }

        ImmutableMap.Builder<Integer, ColumnEncoding> columnEncodingsBuilder = ImmutableMap.builder();
        columnEncodingsBuilder.put(0, new ColumnEncoding(DIRECT, 0));
        columnWriters.forEach(columnWriter -> columnEncodingsBuilder.putAll(columnWriter.getColumnEncodings()));
        Map<Integer, ColumnEncoding> columnEncodings = columnEncodingsBuilder.build();

        // reorder data streams
        streamLayout.reorder(dataStreams, nodeIdToColumn, columnEncodings);
        streamSizeHelper.collectStreamSizes(Iterables.concat(indexStreams, dataStreams), columnEncodings);

        // add data streams
        for (StreamDataOutput dataStream : dataStreams) {
            // The ordering is critical because the stream only contains a length with no offset.
            // if the previous stream was part of a different encryption group, need to specify an offset so we know the column order
            Optional<Integer> encryptionGroup = dwrfEncryptionInfo.getGroupByNodeId(dataStream.getStream().getColumn());
            if (encryptionGroup.isPresent()) {
                Stream stream = previousEncryptionGroup == encryptionGroup.get() ? dataStream.getStream() : dataStream.getStream().withOffset(offset);
                encryptedStreams.put(encryptionGroup.get(), stream);
                previousEncryptionGroup = encryptionGroup.get();
            }
            else {
                Stream stream = previousEncryptionGroup == -1 ? dataStream.getStream() : dataStream.getStream().withOffset(offset);
                unencryptedStreams.add(stream);
                previousEncryptionGroup = -1;
            }
            offset += dataStream.size();
        }

        Map<Integer, ColumnStatistics> columnStatistics = new HashMap<>();
        columnWriters.forEach(columnWriter -> columnStatistics.putAll(columnWriter.getColumnStripeStatistics()));

        // the 0th column is a struct column for the whole row
        columnStatistics.put(0, new ColumnStatistics((long) stripeRowCount, null, stripeRawSize, null));

        Map<Integer, ColumnEncoding> unencryptedColumnEncodings = columnEncodings.entrySet().stream()
                .filter(entry -> !dwrfEncryptionInfo.getGroupByNodeId(entry.getKey()).isPresent())
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));

        Map<Integer, ColumnEncoding> encryptedColumnEncodings = columnEncodings.entrySet().stream()
                .filter(entry -> dwrfEncryptionInfo.getGroupByNodeId(entry.getKey()).isPresent())
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));
        List<Slice> encryptedGroups = createEncryptedGroups(encryptedStreams, encryptedColumnEncodings);

        StripeFooter stripeFooter = new StripeFooter(unencryptedStreams, unencryptedColumnEncodings, encryptedGroups);
        Slice footer = metadataWriter.writeStripeFooter(stripeFooter);
        DataOutput footerDataOutput = createDataOutput(footer);
        dwrfStripeCacheWriter.ifPresent(stripeCacheWriter -> stripeCacheWriter.addStripeFooter(createDataOutput(footer)));

        // create final stripe statistics
        StripeStatistics statistics = new StripeStatistics(toDenseList(columnStatistics, orcTypes.size()));

        recordValidation(validation -> validation.addStripeStatistics(stripeStartOffset, statistics));

        StripeInformation stripeInformation = new StripeInformation(stripeRowCount, stripeStartOffset, indexLength, dataLength, footer.length(), OptionalLong.of(stripeRawSize), dwrfEncryptionInfo.getEncryptedKeyMetadatas());
        ClosedStripe closedStripe = new ClosedStripe(stripeInformation, statistics);
        closedStripes.add(closedStripe);
        closedStripesRetainedBytes += closedStripe.getRetainedSizeInBytes();

        recordValidation(validation -> validation.addStripe(stripeInformation.getNumberOfRows()));
        stats.recordStripeWritten(
                flushPolicy.getStripeMinBytes(),
                flushPolicy.getStripeMaxBytes(),
                dictionaryMaxMemoryBytes,
                flushReason,
                dictionaryCompressionOptimizer.getDictionaryMemoryBytes(),
                stripeInformation);

        return ImmutableList.<DataOutput>builder()
                .addAll(indexStreams)
                .addAll(dataStreams)
                .add(footerDataOutput)
                .build();
    }

    private List<Slice> createEncryptedGroups(Multimap<Integer, Stream> encryptedStreams, Map<Integer, ColumnEncoding> encryptedColumnEncodings)
            throws IOException
    {
        ImmutableList.Builder<Slice> encryptedGroups = ImmutableList.builder();
        for (int i = 0; i < encryptedStreams.keySet().size(); i++) {
            int groupId = i;
            Map<Integer, ColumnEncoding> groupColumnEncodings = encryptedColumnEncodings.entrySet().stream()
                    .filter(entry -> dwrfEncryptionInfo.getGroupByNodeId(entry.getKey()).orElseThrow(() -> new VerifyError("missing group for encryptedColumn")) == groupId)
                    .collect(toImmutableMap(Entry::getKey, Entry::getValue));
            DwrfDataEncryptor dwrfDataEncryptor = dwrfEncryptionInfo.getEncryptorByGroupId(i);
            OrcOutputBuffer buffer = new OrcOutputBuffer(columnWriterOptions, Optional.of(dwrfDataEncryptor));
            toStripeEncryptionGroup(
                    new StripeEncryptionGroup(
                            ImmutableList.copyOf(encryptedStreams.get(i)),
                            groupColumnEncodings))
                    .writeTo(buffer);
            buffer.close();
            DynamicSliceOutput output = new DynamicSliceOutput(toIntExact(buffer.getOutputDataSize()));
            buffer.writeDataTo(output);
            encryptedGroups.add(output.slice());
        }
        return encryptedGroups.build();
    }

    @Override
    public void close()
            throws IOException
    {
        if (closed) {
            return;
        }
        closed = true;
        stats.updateSizeInBytes(-previouslyRecordedSizeInBytes);
        previouslyRecordedSizeInBytes = 0;

        flushStripe(CLOSED);

        dataSink.close();
    }

    /**
     * Collect the data for the file footer.  This is not the actual data, but
     * instead are functions that know how to write the data.
     */
    private List<DataOutput> bufferFileFooter()
            throws IOException
    {
        List<DataOutput> outputData = new ArrayList<>();

        Metadata metadata = new Metadata(closedStripes.stream()
                .map(ClosedStripe::getStatistics)
                .collect(toList()));
        Slice metadataSlice = metadataWriter.writeMetadata(metadata);
        outputData.add(createDataOutput(metadataSlice));

        numberOfRows = closedStripes.stream()
                .mapToLong(stripe -> stripe.getStripeInformation().getNumberOfRows())
                .sum();

        List<ColumnStatistics> fileStats = toFileStats(
                closedStripes.stream()
                        .map(ClosedStripe::getStatistics)
                        .map(StripeStatistics::getColumnStatistics)
                        .collect(toList()),
                streamSizeHelper.getNodeSizes(),
                streamSizeHelper.getMapKeySizes());
        recordValidation(validation -> validation.setFileStatistics(fileStats));

        Map<String, Slice> userMetadata = this.userMetadata.entrySet().stream()
                .collect(Collectors.toMap(Entry::getKey, entry -> utf8Slice(entry.getValue())));

        unencryptedStats = new ArrayList<>();
        Map<Integer, Map<Integer, Slice>> encryptedStats = new HashMap<>();
        addStatsRecursive(fileStats, 0, new HashMap<>(), unencryptedStats, encryptedStats);
        Optional<DwrfEncryption> dwrfEncryption;
        if (dwrfWriterEncryption.isPresent()) {
            ImmutableList.Builder<EncryptionGroup> encryptionGroupBuilder = ImmutableList.builder();
            List<WriterEncryptionGroup> writerEncryptionGroups = dwrfWriterEncryption.get().getWriterEncryptionGroups();
            for (int i = 0; i < writerEncryptionGroups.size(); i++) {
                WriterEncryptionGroup group = writerEncryptionGroups.get(i);
                Map<Integer, Slice> groupStats = encryptedStats.get(i);
                encryptionGroupBuilder.add(
                        new EncryptionGroup(
                                group.getNodes(),
                                Optional.empty(), // reader will just use key metadata from the stripe
                                group.getNodes().stream()
                                        .map(groupStats::get)
                                        .collect(toList())));
            }
            dwrfEncryption = Optional.of(
                    new DwrfEncryption(
                            dwrfWriterEncryption.get().getKeyProvider(),
                            encryptionGroupBuilder.build()));
        }
        else {
            dwrfEncryption = Optional.empty();
        }

        Optional<DwrfStripeCacheData> dwrfStripeCacheData = dwrfStripeCacheWriter.map(DwrfStripeCacheWriter::getDwrfStripeCacheData);
        Slice dwrfStripeCacheSlice = metadataWriter.writeDwrfStripeCache(dwrfStripeCacheData);
        outputData.add(createDataOutput(dwrfStripeCacheSlice));

        Optional<List<Integer>> dwrfStripeCacheOffsets = dwrfStripeCacheWriter.map(DwrfStripeCacheWriter::getOffsets);
        Footer footer = new Footer(
                numberOfRows,
                rowGroupMaxRowCount,
                OptionalLong.of(rawSize),
                closedStripes.stream()
                        .map(ClosedStripe::getStripeInformation)
                        .collect(toList()),
                orcTypes,
                ImmutableList.copyOf(unencryptedStats),
                userMetadata,
                dwrfEncryption,
                dwrfStripeCacheOffsets);

        closedStripes.clear();
        closedStripesRetainedBytes = 0;

        Slice footerSlice = metadataWriter.writeFooter(footer);
        outputData.add(createDataOutput(footerSlice));

        recordValidation(validation -> validation.setVersion(metadataWriter.getOrcMetadataVersion()));
        Slice postscriptSlice = metadataWriter.writePostscript(
                footerSlice.length(),
                metadataSlice.length(),
                columnWriterOptions.getCompressionKind(),
                columnWriterOptions.getCompressionMaxBufferSize(),
                dwrfStripeCacheData);
        outputData.add(createDataOutput(postscriptSlice));
        outputData.add(createDataOutput(Slices.wrappedBuffer((byte) postscriptSlice.length())));
        return outputData;
    }

    private void addStatsRecursive(List<ColumnStatistics> allStats, int index, Map<Integer, List<ColumnStatistics>> nodeAndSubNodeStats, List<ColumnStatistics> unencryptedStats, Map<Integer, Map<Integer, Slice>> encryptedStats)
            throws IOException
    {
        if (allStats.isEmpty()) {
            return;
        }
        ColumnStatistics columnStatistics = allStats.get(index);
        if (dwrfEncryptionInfo.getGroupByNodeId(index).isPresent()) {
            int group = dwrfEncryptionInfo.getGroupByNodeId(index).get();
            boolean isRootNode = dwrfWriterEncryption.get().getWriterEncryptionGroups().get(group).getNodes().contains(index);
            verify(isRootNode && nodeAndSubNodeStats.isEmpty() || nodeAndSubNodeStats.size() == 1 && nodeAndSubNodeStats.get(group) != null,
                    "nodeAndSubNodeStats should only be present for subnodes of a group");
            nodeAndSubNodeStats.computeIfAbsent(group, x -> new ArrayList<>()).add(columnStatistics);
            unencryptedStats.add(new ColumnStatistics(
                    columnStatistics.getNumberOfValues(),
                    null,
                    columnStatistics.hasRawSize() ? columnStatistics.getRawSize() : null,
                    columnStatistics.hasStorageSize() ? columnStatistics.getStorageSize() : null));
            for (Integer fieldIndex : orcTypes.get(index).getFieldTypeIndexes()) {
                addStatsRecursive(allStats, fieldIndex, nodeAndSubNodeStats, unencryptedStats, encryptedStats);
            }
            if (isRootNode) {
                Slice encryptedFileStatistics = toEncryptedFileStatistics(nodeAndSubNodeStats.get(group), group);
                encryptedStats.computeIfAbsent(group, x -> new HashMap<>()).put(index, encryptedFileStatistics);
            }
        }
        else {
            unencryptedStats.add(columnStatistics);
            for (Integer fieldIndex : orcTypes.get(index).getFieldTypeIndexes()) {
                addStatsRecursive(allStats, fieldIndex, new HashMap<>(), unencryptedStats, encryptedStats);
            }
        }
    }

    private Slice toEncryptedFileStatistics(List<ColumnStatistics> statsFromRoot, int groupId)
            throws IOException
    {
        DwrfProto.FileStatistics fileStatistics = toFileStatistics(statsFromRoot);
        DwrfDataEncryptor dwrfDataEncryptor = dwrfEncryptionInfo.getEncryptorByGroupId(groupId);
        OrcOutputBuffer buffer = new OrcOutputBuffer(columnWriterOptions, Optional.of(dwrfDataEncryptor));
        fileStatistics.writeTo(buffer);
        buffer.close();
        DynamicSliceOutput output = new DynamicSliceOutput(toIntExact(buffer.getOutputDataSize()));
        buffer.writeDataTo(output);
        return output.slice();
    }

    private void recordValidation(Consumer<OrcWriteValidationBuilder> task)
    {
        if (validationBuilder != null) {
            task.accept(validationBuilder);
        }
    }

    public void validate(OrcDataSource input)
            throws OrcCorruptionException
    {
        checkState(validationBuilder != null, "validation is not enabled");
        ImmutableMap.Builder<Integer, Slice> intermediateKeyMetadata = ImmutableMap.builder();
        if (dwrfWriterEncryption.isPresent()) {
            List<WriterEncryptionGroup> writerEncryptionGroups = dwrfWriterEncryption.get().getWriterEncryptionGroups();
            for (int i = 0; i < writerEncryptionGroups.size(); i++) {
                for (Integer node : writerEncryptionGroups.get(i).getNodes()) {
                    intermediateKeyMetadata.put(node, writerEncryptionGroups.get(i).getIntermediateKeyMetadata());
                }
            }
        }

        validateFile(
                validationBuilder.build(),
                input,
                types,
                hiveStorageTimeZone,
                orcEncoding,
                OrcReaderOptions.builder()
                        .withMaxMergeDistance(new DataSize(1, MEGABYTE))
                        .withTinyStripeThreshold(new DataSize(8, MEGABYTE))
                        .withMaxBlockSize(new DataSize(16, MEGABYTE))
                        .build(),
                dwrfEncryptionProvider,
                DwrfKeyProvider.of(intermediateKeyMetadata.build()));
    }

    public long getFileRowCount()
    {
        checkState(closed, "File row count is not available until the writing has finished");
        return numberOfRows;
    }

    public List<ColumnStatistics> getFileStats()
    {
        checkState(closed, "File statistics are not available until the writing has finished");
        return unencryptedStats;
    }

    private static <T> List<T> toDenseList(Map<Integer, T> data, int expectedSize)
    {
        checkArgument(data.size() == expectedSize);
        if (expectedSize == 0) {
            return ImmutableList.of();
        }

        List<Integer> sortedKeys = new ArrayList<>(data.keySet());
        Collections.sort(sortedKeys);

        ImmutableList.Builder<T> denseList = ImmutableList.builderWithExpectedSize(expectedSize);
        for (Integer key : sortedKeys) {
            denseList.add(data.get(key));
        }
        return denseList.build();
    }

    private static List<ColumnStatistics> toFileStats(List<List<ColumnStatistics>> stripes, Int2LongMap nodeSizes, Int2ObjectMap<Object2LongMap<DwrfProto.KeyInfo>> mapKeySizes)
    {
        if (stripes.isEmpty()) {
            return ImmutableList.of();
        }

        int columnCount = stripes.get(0).size();
        checkArgument(stripes.stream().allMatch(stripe -> columnCount == stripe.size()));

        ImmutableList.Builder<ColumnStatistics> fileStats = ImmutableList.builder();
        for (int i = 0; i < columnCount; i++) {
            int column = i;
            List<ColumnStatistics> stripeColumnStats = stripes.stream()
                    .map(stripe -> stripe.get(column))
                    .collect(toList());
            long storageSize = nodeSizes.getOrDefault(column, 0L);
            Object2LongMap<DwrfProto.KeyInfo> keySizes = mapKeySizes.get(column);
            ColumnStatistics columnStats = mergeColumnStatistics(stripeColumnStats, storageSize, keySizes);
            fileStats.add(columnStats);
        }
        return fileStats.build();
    }

    private static class ClosedStripe
    {
        private static final int INSTANCE_SIZE = ClassLayout.parseClass(ClosedStripe.class).instanceSize() + ClassLayout.parseClass(StripeInformation.class).instanceSize();

        private final StripeInformation stripeInformation;
        private final StripeStatistics statistics;

        public ClosedStripe(StripeInformation stripeInformation, StripeStatistics statistics)
        {
            this.stripeInformation = requireNonNull(stripeInformation, "stripeInformation is null");
            this.statistics = requireNonNull(statistics, "stripeStatistics is null");
        }

        public StripeInformation getStripeInformation()
        {
            return stripeInformation;
        }

        public StripeStatistics getStatistics()
        {
            return statistics;
        }

        public long getRetainedSizeInBytes()
        {
            return INSTANCE_SIZE + statistics.getRetainedSizeInBytes();
        }
    }
}
