/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 *  Copyright 2013 Josh Elser
 *
 */
package cosmos.mapred;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import org.mediawiki.xml.export_0.MediaWikiType;
import org.mediawiki.xml.export_0.PageType;

import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;

import cosmos.Cosmos;
import cosmos.impl.CosmosImpl;
import cosmos.impl.SortableResult;
import cosmos.mediawiki.MediawikiPage.Page;
import cosmos.mediawiki.MediawikiPage.Page.Revision;
import cosmos.mediawiki.MediawikiPage.Page.Revision.Contributor;
import cosmos.options.Defaults;
import cosmos.options.Index;
import cosmos.results.CloseableIterable;
import cosmos.results.Column;
import cosmos.results.SValue;
import cosmos.results.impl.MultimapQueryResult;
import cosmos.results.integration.CosmosIntegrationSetup;
import cosmos.util.IdentitySet;

/**
 * 
 */
public class MediawikiQueries {
  public static final boolean preloadData = false;
  
  public static final String TIMINGS = "[TIMINGS] ";
  public static final int MAX_SIZE = 8000;
  
  // MAX_OFFSET is a little misleading because the max pageID is 33928886
  // Don't have contiguous pageIDs
  public static final int MAX_OFFSET = 11845576 - MAX_SIZE;
  
  public static final int MAX_ROW = 999999999;
  
  public static final ColumnVisibility cv = new ColumnVisibility("en");
  
  public static final Column PAGE_ID = Column.create("PAGE_ID"), REVISION_ID = Column.create("REVISION_ID"), REVISION_TIMESTAMP = Column
      .create("REVISION_TIMESTAMP"), CONTRIBUTOR_USERNAME = Column.create("CONTRIBUTOR_USERNAME"), CONTRIBUTOR_ID = Column.create("CONTRIBUTOR_ID");
  
  public static void logTiming(long numResults, long duration, String action) {
    System.err.println(TIMINGS + numResults + " " + duration + " " + action);
  }
  
  public static MultimapQueryResult pagesToQueryResult(Page p) {
    HashMultimap<Column,SValue> data = HashMultimap.create();
    
    String pageId = Long.toString(p.getId());
    
    data.put(PAGE_ID, SValue.create(pageId, cv));
    
    Revision r = p.getRevision();
    if (null != r) {
      data.put(REVISION_ID, SValue.create(Long.toString(r.getId()), cv));
      data.put(REVISION_TIMESTAMP, SValue.create(r.getTimestamp(), cv));
      
      Contributor c = r.getContributor();
      if (null != c) {
        if (null != c.getUsername()) {
          data.put(CONTRIBUTOR_USERNAME, SValue.create(c.getUsername(), cv));
        }
        
        if (0l != c.getId()) {
          data.put(CONTRIBUTOR_ID, SValue.create(Long.toString(c.getId()), cv));
        }
      }
    }
    
    return new MultimapQueryResult(data, pageId, cv);
  }
  
  protected final Connector con;
  protected final Cosmos sorts;
  
  public MediawikiQueries() throws Exception {
    ZooKeeperInstance zk = new ZooKeeperInstance("accumulo1.5", "localhost");
    this.con = zk.getConnector("mediawiki", new PasswordToken("password"));
    
    this.sorts = new CosmosImpl("localhost");
  }
  
  public void run(int numIterations) throws Exception {
    final Random offsetR = new Random(), cardinalityR = new Random();
    
    int iters = 0;
    
    while (iters < numIterations) {
      SortableResult id = SortableResult.create(this.con, this.con.securityOperations().getUserAuthorizations(this.con.whoami()), IdentitySet.<Index> create());
      
      int offset = offsetR.nextInt(MAX_OFFSET);
      int numRecords = cardinalityR.nextInt(MAX_SIZE) + 1;
      
      BatchScanner bs = this.con.createBatchScanner("sortswiki", new Authorizations(), 4);
      
      bs.setRanges(Collections.singleton(new Range(Integer.toString(offset), Integer.toString(MAX_ROW))));
      
      Iterable<Entry<Key,Value>> inputIterable = Iterables.limit(bs, numRecords);
      
      this.sorts.register(id);
      
      System.out.println(Thread.currentThread().getName() + ": " + id.uuid() + " - Iteration " + iters);
      long recordsReturned = 0l;
      Function<Entry<Key,Value>,MultimapQueryResult> func = new Function<Entry<Key,Value>,MultimapQueryResult>() {
        @Override
        public MultimapQueryResult apply(Entry<Key,Value> input) {
          Page p;
          try {
            p = Page.parseFrom(input.getValue().get());
          } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
          }
          return pagesToQueryResult(p);
        }
      };
      
      Map<Column,Long> counts = Maps.newHashMap();
      ArrayList<MultimapQueryResult> tformSource = Lists.newArrayListWithCapacity(20000);
      
      Stopwatch sw = new Stopwatch();
      Stopwatch tformSw = new Stopwatch();
      
      for (Entry<Key,Value> input : inputIterable) {
        tformSw.start();
        
        MultimapQueryResult r = func.apply(input);
        tformSource.add(r);
        
        tformSw.stop();
        
        loadCountsForRecord(counts, r);
        recordsReturned++;
      }
      
      sw.start();
      this.sorts.addResults(id, tformSource);
      sw.stop();
      
      long actualNumResults = tformSource.size();
      
      System.out.println(Thread.currentThread().getName() + ": Took " + tformSw + " transforming and " + sw + " to store " + recordsReturned + " records");
      logTiming(actualNumResults, tformSw.elapsed(TimeUnit.MILLISECONDS), "transformInput");
      logTiming(actualNumResults, sw.elapsed(TimeUnit.MILLISECONDS), "ingest");

      bs.close();
      
      Random r = new Random();
      int max = r.nextInt(10) + 1;
      
      // Run a bunch of queries
      for (int count = 0; count < max; count++) {
        long resultCount;
        String name;
        int i = r.nextInt(9);
        
        if (0 == i) {
          resultCount = docIdFetch(id, counts, actualNumResults);
          name = "docIdFetch";
        } else if (1 == i) {
          resultCount = columnFetch(id, REVISION_ID, counts, actualNumResults);
          name = "revisionIdFetch";
        } else if (2 == i) {
          resultCount = columnFetch(id, PAGE_ID, counts, actualNumResults);
          name = "pageIdFetch";
        } else if (3 == i) {
          groupBy(id, REVISION_ID, counts, actualNumResults);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByRevisionId";
        } else if (4 == i) {
          groupBy(id, PAGE_ID, counts, actualNumResults);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByRevisionId";
        } else if (5 == i) {
          resultCount = columnFetch(id, CONTRIBUTOR_USERNAME, counts, actualNumResults);
          name = "contributorUsernameFetch";
        } else if (6 == i) {
          groupBy(id, CONTRIBUTOR_USERNAME, counts, actualNumResults);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByContributorUsername";
        } else if (7 == i) {
          resultCount = columnFetch(id, CONTRIBUTOR_ID, counts, actualNumResults);
          name = "contributorIdFetch";
        } else {//if (8 == i) {
          groupBy(id, CONTRIBUTOR_ID, counts, actualNumResults);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByContributorID";
        }
      }
      
      // Delete the results
      sw = new Stopwatch();
      
      sw.start();
      this.sorts.delete(id);
      sw.stop();
      
      System.out.println(Thread.currentThread().getName() + ": Took " + sw.toString() + " to delete results");
      logTiming(actualNumResults, sw.elapsed(TimeUnit.MILLISECONDS), "deleteResults");
      
      iters++;
    }
    
    this.sorts.close();
  }
  
  public void loadCountsForRecord(Map<Column,Long> counts, MultimapQueryResult r) {
	  for (Entry<Column,SValue> entry : r.columnValues()) {
		  Column c = entry.getKey();
		  if (counts.containsKey(c)) {
			  counts.put(c, counts.get(c)+1);
		  } else {
			  counts.put(c, 1l);
		  }
	  }
  }
  
  public long docIdFetch(SortableResult id, Map<Column,Long> counts, long totalResults) throws Exception {
    Stopwatch sw = new Stopwatch();
    
    // This is dumb, I didn't pad the docids...
    String prev = "!";
    long resultCount = 0l;
    sw.start();
    
    final CloseableIterable<MultimapQueryResult> results = this.sorts.fetch(id, Index.define(Defaults.DOCID_FIELD_NAME));
    
    for (MultimapQueryResult r : results) {
      sw.stop();
      
      resultCount++;
      
      String current = r.docId();
      if (prev.compareTo(current) > 0) {
        System.out.println("WOAH, got " + current + " docid which was greater than the previous " + prev);
        results.close();
        System.exit(1);
      }
      
      prev = current;
      
      sw.start();
    }
    
    sw.stop();
    
    System.out.println(Thread.currentThread().getName() + ": docIdFetch - Took " + sw.toString() + " to fetch results");
    logTiming(totalResults, sw.elapsed(TimeUnit.MILLISECONDS), "docIdFetch");
    
    results.close();
    
    return resultCount;
  }
  
  public long columnFetch(SortableResult id, Column colToFetch, Map<Column,Long> counts, long totalResults) throws Exception {
    Stopwatch sw = new Stopwatch();
    String prev = null;
    String lastDocId = null;
    long resultCount = 0l;
    
    sw.start();
    final CloseableIterable<MultimapQueryResult> results = this.sorts.fetch(id, Index.define(colToFetch));
    Iterator<MultimapQueryResult> resultsIter = results.iterator();
    
    for (; resultsIter.hasNext();) {
      MultimapQueryResult r = resultsIter.next();
      
      sw.stop();
      resultCount++;
      
      Collection<SValue> values = r.get(colToFetch);
      
      TreeSet<SValue> sortedValues = Sets.newTreeSet(values);
      
      if (null == prev) {
        prev = sortedValues.first().value();
      } else {
        boolean plausible = false;
        Iterator<SValue> iter = sortedValues.iterator();
        for (; !plausible && iter.hasNext();) {
          String val = iter.next().value();
          if (prev.compareTo(val) <= 0) {
            plausible = true;
          }
        }
        
        if (!plausible) {
          System.out.println(Thread.currentThread().getName() + ": " + colToFetch + " - " + lastDocId + " shouldn't have come before " + r.docId());
          System.out.println(prev + " compared to " + sortedValues);
          results.close();
          System.exit(1);
        }
      }
      
      lastDocId = r.docId();
      
      sw.start();
    }
    
    sw.stop();
    
    System.out.println(Thread.currentThread().getName() + ": " + colToFetch + " - Took " + sw.toString() + " to fetch results");
    logTiming(totalResults, sw.elapsed(TimeUnit.MILLISECONDS), "fetch:" + colToFetch);

    results.close();
    
    long expected = counts.containsKey(colToFetch) ? counts.get(colToFetch) : -1;
    
    if (resultCount != expected) {
      System.out.println(Thread.currentThread().getName() + " " + colToFetch + ": Expected to get " + expected + " records but got " + resultCount);
      System.exit(1);
    }
    
    return resultCount;
  }
  
  public void groupBy(SortableResult id, Column colToFetch, Map<Column,Long> columnCounts, long totalResults) throws Exception {
    Stopwatch sw = new Stopwatch();
    
    sw.start();
    final CloseableIterable<Entry<SValue,Long>> results = this.sorts.groupResults(id, colToFetch);
    TreeMap<SValue,Long> counts = Maps.newTreeMap();
    
    for (Entry<SValue,Long> entry : results) {
      counts.put(entry.getKey(), entry.getValue());
    }
    
    results.close();
    sw.stop();
    
    System.out.println(Thread.currentThread().getName() + ": " + colToFetch + " - Took " + sw.toString() + " to group results");
    logTiming(totalResults, sw.elapsed(TimeUnit.MILLISECONDS), "groupBy:" + colToFetch);

//    System.out.println(counts);
    
    final CloseableIterable<MultimapQueryResult> verifyResults = this.sorts.fetch(id, Index.define(colToFetch));
    TreeMap<SValue,Long> records = Maps.newTreeMap();
    for (MultimapQueryResult r : verifyResults) {
      if (r.containsKey(colToFetch)) {
        for (SValue val : r.get(colToFetch)) {
          if (records.containsKey(val)) {
            records.put(val, records.get(val) + 1);
          } else {
            records.put(val, 1l);
          }
        }
      }
    }

    verifyResults.close();
    
    if (counts.size() != records.size()) {
      System.out.println(Thread.currentThread().getName() + ": " + colToFetch + " - Expected " + records.size() + " groups but found " + counts.size());
      System.exit(1);
    }
    
    Set<SValue> countKeys= counts.keySet(), recordKeys = records.keySet();
    for (SValue k : countKeys) {
      if (!recordKeys.contains(k)) {
        System.out.println(Thread.currentThread().getName() + ": " + colToFetch + " - Expected to have count for " + k); 
        System.exit(1); 
      }
      
      Long actual = counts.get(k), expected = records.get(k);
      
      if (!actual.equals(expected)) {
        System.out.println(Thread.currentThread().getName() + ": " + colToFetch + " - Expected " + expected + " value(s) but found " + actual + " value(s) for " + k.value());
        System.exit(1);
      }
    }
  }
  
  public static Runnable runQueries(final int numQueries) {
    return new Runnable() {
      public void run() {
        try {
          (new MediawikiQueries()).run(numQueries);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    };
  }
  
  public static void main(String[] args) throws Exception {
    if (preloadData) {
      CosmosIntegrationSetup.initializeJaxb();
      MediawikiQueries queries = new MediawikiQueries();
      MediawikiMapper mapper = new MediawikiMapper();
      mapper.setup(null);
      
      List<MediaWikiType> results = Lists.newArrayList(CosmosIntegrationSetup.getWiki1(), CosmosIntegrationSetup.getWiki2(), CosmosIntegrationSetup.getWiki3(), CosmosIntegrationSetup.getWiki4(), CosmosIntegrationSetup.getWiki5());
      
      try {
        queries.con.tableOperations().create("sortswiki");
      } catch (TableExistsException e) {
        
      }
      BatchWriter bw = queries.con.createBatchWriter("sortswiki", new BatchWriterConfig());
      int i = 0;
      for (MediaWikiType wiki : results) {
        for (PageType pageType : wiki.getPage()) {
          Page page = mapper.pageTypeToPage(pageType);
          Value v = new Value(page.toByteArray());
          
          Mutation m = new Mutation(Integer.toString(i));
          m.put(new Text(), new Text(), v);
          bw.addMutation(m);
          i++;
        }
        bw.flush();
      }
      
      bw.close();
    }
    
    ExecutorService runner = Executors.newFixedThreadPool(3);
    for (int i = 0; i < 4; i++) {
      runner.execute(runQueries(200));
    }
    
    runner.shutdown();
    runner.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
  }
}
