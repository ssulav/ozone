/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.ozone.om.ratis;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.audit.AuditLogger;
import org.apache.hadoop.ozone.audit.AuditMessage;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.ratis.metrics.OzoneManagerDoubleBufferMetrics;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.bucket.OMBucketCreateResponse;
import org.apache.hadoop.ozone.om.response.key.OMKeyCreateResponse;
import org.apache.hadoop.ozone.om.response.snapshot.OMSnapshotCreateResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateSnapshotResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * This class tests snapshot aware OzoneManagerDoubleBuffer flushing logic.
 */
class TestOzoneManagerDoubleBuffer {

  private OzoneManagerDoubleBuffer doubleBuffer;
  private CreateSnapshotResponse snapshotResponse1 =
      mock(CreateSnapshotResponse.class);
  private CreateSnapshotResponse snapshotResponse2 =
      mock(CreateSnapshotResponse.class);
  private OMResponse omKeyResponse = mock(OMResponse.class);
  private OMResponse omBucketResponse = mock(OMResponse.class);
  private OMResponse omSnapshotResponse1 = mock(OMResponse.class);
  private OMResponse omSnapshotResponse2 = mock(OMResponse.class);
  private static OMClientResponse omKeyCreateResponse =
      mock(OMKeyCreateResponse.class);
  private static OMClientResponse omBucketCreateResponse =
      mock(OMBucketCreateResponse.class);
  private static OMClientResponse omSnapshotCreateResponse1 =
      mock(OMSnapshotCreateResponse.class);
  private static OMClientResponse omSnapshotCreateResponse2 =
      mock(OMSnapshotCreateResponse.class);
  @TempDir
  private File tempDir;

  @BeforeEach
  public void setup() throws IOException {
    OMMetrics omMetrics = OMMetrics.create();
    OzoneConfiguration ozoneConfiguration = new OzoneConfiguration();
    ozoneConfiguration.set(OMConfigKeys.OZONE_OM_DB_DIRS,
        tempDir.getAbsolutePath());
    OMMetadataManager omMetadataManager =
        new OmMetadataManagerImpl(ozoneConfiguration);
    OzoneManager ozoneManager = mock(OzoneManager.class);
    when(ozoneManager.getMetrics()).thenReturn(omMetrics);
    when(ozoneManager.getMetadataManager()).thenReturn(omMetadataManager);
    when(ozoneManager.getMaxUserVolumeCount()).thenReturn(10L);
    AuditLogger auditLogger = mock(AuditLogger.class);
    when(ozoneManager.getAuditLogger()).thenReturn(auditLogger);
    Mockito.doNothing().when(auditLogger).logWrite(any(AuditMessage.class));
    OzoneManagerRatisSnapshot ozoneManagerRatisSnapshot = index -> {
    };

    doubleBuffer = new OzoneManagerDoubleBuffer.Builder()
        .setOmMetadataManager(omMetadataManager)
        .setOzoneManagerRatisSnapShot(ozoneManagerRatisSnapshot)
        .setmaxUnFlushedTransactionCount(1000)
        .enableRatis(true)
        .setIndexToTerm((i) -> 1L)
        .build();

    doNothing().when(omKeyCreateResponse).checkAndUpdateDB(any(), any());
    doNothing().when(omBucketCreateResponse).checkAndUpdateDB(any(), any());
    doNothing().when(omSnapshotCreateResponse1).checkAndUpdateDB(any(), any());
    doNothing().when(omSnapshotCreateResponse2).checkAndUpdateDB(any(), any());

    when(omKeyResponse.getTraceID()).thenReturn("keyTraceId");
    when(omBucketResponse.getTraceID()).thenReturn("bucketTraceId");
    when(omSnapshotResponse1.getTraceID()).thenReturn("snapshotTraceId-1");
    when(omSnapshotResponse2.getTraceID()).thenReturn("snapshotTraceId-2");
    when(omSnapshotResponse1.hasCreateSnapshotResponse())
        .thenReturn(true);
    when(omSnapshotResponse2.hasCreateSnapshotResponse())
        .thenReturn(true);
    when(omSnapshotResponse1.getCreateSnapshotResponse())
        .thenReturn(snapshotResponse1);
    when(omSnapshotResponse2.getCreateSnapshotResponse())
        .thenReturn(snapshotResponse2);

    when(omKeyCreateResponse.getOMResponse()).thenReturn(omKeyResponse);
    when(omBucketCreateResponse.getOMResponse()).thenReturn(omBucketResponse);
    when(omSnapshotCreateResponse1.getOMResponse())
        .thenReturn(omSnapshotResponse1);
    when(omSnapshotCreateResponse2.getOMResponse())
        .thenReturn(omSnapshotResponse2);
  }

  @AfterEach
  public void stop() {
    if (doubleBuffer != null) {
      doubleBuffer.stop();
    }
  }

  private static Stream<Arguments> doubleBufferFlushCases() {
    return Stream.of(
        Arguments.of(Arrays.asList(omKeyCreateResponse,
                omBucketCreateResponse),
            1L, 2L, 1L, 2L, 2L, 2.0F),
        Arguments.of(Arrays.asList(omSnapshotCreateResponse1,
                omSnapshotCreateResponse2),
            2L, 2L, 3L, 4L, 1L, 1.333F),
        Arguments.of(Arrays.asList(omKeyCreateResponse,
                omBucketCreateResponse,
                omSnapshotCreateResponse1,
                omSnapshotCreateResponse2),
            3L, 4L, 6L, 8L, 2L, 1.333F),
        Arguments.of(Arrays.asList(omKeyCreateResponse,
                omSnapshotCreateResponse1,
                omBucketCreateResponse,
                omSnapshotCreateResponse2),
            4L, 4L, 10L, 12L, 1L, 1.200F),
        Arguments.of(Arrays.asList(omKeyCreateResponse,
                omSnapshotCreateResponse1,
                omSnapshotCreateResponse2,
                omBucketCreateResponse),
            4L, 4L, 14L, 16L, 1L, 1.142F)
    );
  }

  /**
   * Tests OzoneManagerDoubleBuffer's snapshot aware splitting and flushing
   * logic.
   *
   * @param expectedFlushCounts, Total flush count per OzoneManagerDoubleBuffer.
   * @param expectedFlushedTransactionCount, Total transaction count per
   *                                         OzoneManagerDoubleBuffer.
   * @param expectedFlushCountsInMetric, Overall flush count, and it is not
   *                                     same as expectedFlushCounts because
   *                                     metric static and shared object.
   * @param expectedFlushedTransactionCountInMetric, Overall transaction count.
   * @param expectedMaxNumberOfTransactionsFlushedInMetric, Overall max
   *                                                        transaction count
   *                                                        per flush.
   * @param expectedAvgFlushTransactionsInMetric, Overall avg transaction count
   *                                              per flush.
   */
  @ParameterizedTest
  @MethodSource("doubleBufferFlushCases")
  public void testOzoneManagerDoubleBuffer(
      List<OMClientResponse> omClientResponses,
      long expectedFlushCounts,
      long expectedFlushedTransactionCount,
      long expectedFlushCountsInMetric,
      long expectedFlushedTransactionCountInMetric,
      long expectedMaxNumberOfTransactionsFlushedInMetric,
      float expectedAvgFlushTransactionsInMetric
  ) {

    // Stop the daemon till to eliminate the race condition.
    doubleBuffer.stopDaemon();

    for (int i = 0; i < omClientResponses.size(); i++) {
      doubleBuffer.add(omClientResponses.get(i), i);
    }

    // Flush the current buffer.
    doubleBuffer.flushCurrentBuffer();

    assertEquals(expectedFlushCounts, doubleBuffer.getFlushIterations());
    assertEquals(expectedFlushedTransactionCount,
        doubleBuffer.getFlushedTransactionCount());

    OzoneManagerDoubleBufferMetrics bufferMetrics =
        doubleBuffer.getOzoneManagerDoubleBufferMetrics();

    assertEquals(expectedFlushCountsInMetric,
        bufferMetrics.getTotalNumOfFlushOperations());
    assertEquals(expectedFlushedTransactionCountInMetric,
        bufferMetrics.getTotalNumOfFlushedTransactions());
    assertEquals(expectedMaxNumberOfTransactionsFlushedInMetric,
        bufferMetrics.getMaxNumberOfTransactionsFlushedInOneIteration());
    assertEquals(expectedAvgFlushTransactionsInMetric,
        bufferMetrics.getAvgFlushTransactionsInOneIteration(), 0.001);
  }
}
