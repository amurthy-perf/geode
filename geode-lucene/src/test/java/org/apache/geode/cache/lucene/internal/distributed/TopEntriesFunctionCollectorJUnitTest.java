/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.cache.lucene.internal.distributed;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import org.apache.geode.CancelCriterion;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.internal.cache.GemFireCacheImpl;
import org.apache.geode.test.junit.categories.UnitTest;

@Category(UnitTest.class)
public class TopEntriesFunctionCollectorJUnitTest {
  EntryScore<String> r1_1;
  EntryScore<String> r1_2;
  EntryScore<String> r2_1;
  EntryScore<String> r2_2;
  TopEntriesCollector result1, result2;

  @Before
  public void initializeCommonObjects() {
    r1_1 = new EntryScore<String>("3", .9f);
    r1_2 = new EntryScore<String>("1", .8f);
    r2_1 = new EntryScore<String>("2", 0.85f);
    r2_2 = new EntryScore<String>("4", 0.1f);

    result1 = new TopEntriesCollector(null);
    result1.collect(r1_1);
    result1.collect(r1_2);

    result2 = new TopEntriesCollector(null);
    result2.collect(r2_1);
    result2.collect(r2_2);
  }

  @Test
  public void testGetResultsBlocksTillEnd() throws Exception {
    final TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector();
    TopEntries merged = collector.getResult();
    assertEquals(0, merged.size());
  }

  @Test
  public void testGetResultsTimedWait() throws Exception {
    final TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector();
    collector.addResult(null, result1);
    collector.addResult(null, result2);

    final CountDownLatch insideThread = new CountDownLatch(1);
    final CountDownLatch resultReceived = new CountDownLatch(1);

    final AtomicReference<TopEntries> result = new AtomicReference<>();
    TopEntries merged = collector.getResult(1, TimeUnit.SECONDS);
    assertEquals(4, merged.size());
    TopEntriesJUnitTest.verifyResultOrder(merged.getHits(), r1_1, r2_1, r1_2, r2_2);
  }

  @Test
  public void mergeShardAndLimitResults() throws Exception {
    LuceneFunctionContext<TopEntriesCollector> context =
        new LuceneFunctionContext<>(null, null, null, 3);

    TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector(context);
    collector.addResult(null, result1);
    collector.addResult(null, result2);
    collector.endResults();

    TopEntries merged = collector.getResult();
    Assert.assertNotNull(merged);
    assertEquals(3, merged.size());
    TopEntriesJUnitTest.verifyResultOrder(merged.getHits(), r1_1, r2_1, r1_2);
  }

  @Test
  public void mergeResultsDefaultCollectorManager() throws Exception {
    TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector();
    collector.addResult(null, result1);
    collector.addResult(null, result2);
    collector.endResults();

    TopEntries merged = collector.getResult();
    Assert.assertNotNull(merged);
    assertEquals(4, merged.size());
    TopEntriesJUnitTest.verifyResultOrder(merged.getHits(), r1_1, r2_1, r1_2, r2_2);
  }

  @Test
  public void getResultsTwice() throws Exception {
    TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector();
    collector.addResult(null, result1);
    collector.addResult(null, result2);
    collector.endResults();

    TopEntries merged = collector.getResult();
    Assert.assertNotNull(merged);
    assertEquals(4, merged.size());
    TopEntriesJUnitTest.verifyResultOrder(merged.getHits(), r1_1, r2_1, r1_2, r2_2);

    merged = collector.getResult();
    Assert.assertNotNull(merged);
    assertEquals(4, merged.size());
    TopEntriesJUnitTest.verifyResultOrder(merged.getHits(), r1_1, r2_1, r1_2, r2_2);
  }

  @Test
  public void mergeResultsCustomCollectorManager() throws Exception {
    TopEntries resultEntries = new TopEntries();
    TopEntriesCollector mockCollector = mock(TopEntriesCollector.class);
    Mockito.doReturn(resultEntries).when(mockCollector).getEntries();

    CollectorManager<TopEntriesCollector> mockManager = mock(CollectorManager.class);
    Mockito.doReturn(mockCollector).when(mockManager)
        .reduce(Mockito.argThat(new ArgumentMatcher<Collection<TopEntriesCollector>>() {
          @Override
          public boolean matches(Object argument) {
            Collection<TopEntriesCollector> collectors = (Collection<TopEntriesCollector>) argument;
            return collectors.contains(result1) && collectors.contains(result2);
          }
        }));

    LuceneFunctionContext<TopEntriesCollector> context =
        new LuceneFunctionContext<>(null, null, mockManager);
    TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector(context);
    collector.addResult(null, result1);
    collector.addResult(null, result2);
    collector.endResults();

    TopEntries merged = collector.getResult();
    assertEquals(resultEntries, merged);
  }

  @Test
  public void mergeAfterClearResults() throws Exception {
    TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector();
    collector.addResult(null, result1);
    collector.clearResults();
    collector.addResult(null, result2);
    collector.endResults();

    TopEntries merged = collector.getResult();
    Assert.assertNotNull(merged);
    assertEquals(2, merged.size());
    TopEntriesJUnitTest.verifyResultOrder(merged.getHits(), r2_1, r2_2);
  }

  @Test(expected = RuntimeException.class)
  public void testExceptionDuringMerge() throws Exception {
    TopEntriesCollectorManager mockManager = mock(TopEntriesCollectorManager.class);
    Mockito.doThrow(new RuntimeException()).when(mockManager).reduce(any(Collection.class));

    LuceneFunctionContext<TopEntriesCollector> context =
        new LuceneFunctionContext<>(null, null, mockManager);
    TopEntriesFunctionCollector collector = new TopEntriesFunctionCollector(context);
    collector.endResults();
    collector.getResult();
  }

  @Test
  public void testCollectorName() {
    GemFireCacheImpl mockCache = mock(GemFireCacheImpl.class);
    Mockito.doReturn("server").when(mockCache).getName();

    TopEntriesFunctionCollector function = new TopEntriesFunctionCollector(null, mockCache);
    assertEquals("server", function.id);
  }
}
