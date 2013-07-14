package sorts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.ColumnVisibility;

import sorts.impl.SortableResult;
import sorts.impl.SortingImpl;
import sorts.mediawiki.MediawikiPage.Page;
import sorts.mediawiki.MediawikiPage.Page.Revision;
import sorts.mediawiki.MediawikiPage.Page.Revision.Contributor;
import sorts.options.Defaults;
import sorts.options.Index;
import sorts.results.CloseableIterable;
import sorts.results.Column;
import sorts.results.SValue;
import sorts.results.impl.MultimapQueryResult;
import sorts.util.IdentitySet;

import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.protobuf.InvalidProtocolBufferException;

/**
 * 
 */
public class MediawikiQueries {
  public static final int MAX_SIZE = 16000;
  
  // MAX_OFFSET is a little misleading because the max pageID is 33928886
  // Don't have contiguous pageIDs
  public static final int MAX_OFFSET = 11845576 - MAX_SIZE;
  
  public static final int MAX_ROW = 999999999;
  
  public static final ColumnVisibility cv = new ColumnVisibility("en");
  
  public static final Column PAGE_ID = Column.create("PAGE_ID"), REVISION_ID = Column.create("REVISION_ID"), REVISION_TIMESTAMP = Column
      .create("REVISION_TIMESTAMP"), CONTRIBUTOR_USERNAME = Column.create("CONTRIBUTOR_USERNAME"), CONTRIBUTOR_ID = Column.create("CONTRIBUTOR_ID");
  
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
  protected final Sorting sorts;
  
  public MediawikiQueries() throws Exception {
    ZooKeeperInstance zk = new ZooKeeperInstance("accumulo1.5", "localhost");
    this.con = zk.getConnector("mediawiki", new PasswordToken("password"));
    
    this.sorts = new SortingImpl("localhost");
  }
  
  public void run(int numIterations) throws Exception {
    final Random offsetR = new Random(), cardinalityR = new Random();
    
    int iters = 0;
    
    while (iters < numIterations) {
      SortableResult id = SortableResult.create(this.con, this.con.securityOperations().getUserAuthorizations(this.con.whoami()), IdentitySet.<Index> create());
      
      int offset = offsetR.nextInt(MAX_OFFSET);
      int numRecords = cardinalityR.nextInt(MAX_SIZE);
      
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
      
      ArrayList<MultimapQueryResult> tformSource = Lists.newArrayListWithCapacity(20000);
      
      Stopwatch sw = new Stopwatch();
      Stopwatch tformSw = new Stopwatch();
      
      for (Entry<Key,Value> input : inputIterable) {
        tformSw.start();
        tformSource.add(func.apply(input));
        tformSw.stop();
        recordsReturned++;
      }
      
      sw.start();
      this.sorts.addResults(id, tformSource);
      sw.stop();
      
      System.out.println(Thread.currentThread().getName() + ": Took " + tformSw + " transforming and " + sw + " to store " + recordsReturned + " records");
      bs.close();
      
      // Run a bunch of queries
      for (int i = 0; i < 7; i++) {
        long resultCount;
        String name;
        
        if (0 == i) {
          resultCount = docIdFetch(id);
          name = "docIdFetch";
        } else if (1 == i) {
          resultCount = columnFetch(id, REVISION_ID);
          name = "revisionIdFetch";
        } else if (2 == i) {
          resultCount = columnFetch(id, PAGE_ID);
          name = "pageIdFetch";
        } else if (3 == i) {
          groupBy(id, REVISION_ID);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByRevisionId";
        } else if (4 == i) {
          groupBy(id, PAGE_ID);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByRevisionId";
        } else if (5 == i) {
          resultCount = columnFetch(id, CONTRIBUTOR_USERNAME);
          name = "contributorUsernameFetch";
        } else {
          groupBy(id, CONTRIBUTOR_USERNAME);
          // no sense to verify here
          resultCount = recordsReturned;
          name = "groupByContributorUsername";
        }
        
        if (resultCount != recordsReturned) {
          System.out.println(Thread.currentThread().getName() + " " + name + ": Expected to get " + recordsReturned + " records but got " + resultCount);
          System.exit(1);
        }
      }
      
      // Delete the results
      sw = new Stopwatch();
      
      sw.start();
      this.sorts.delete(id);
      sw.stop();
      
      System.out.println(Thread.currentThread().getName() + ": Took " + sw.toString() + " to delete results");
      
      iters++;
    }
    
    this.sorts.close();
  }
  
  public long docIdFetch(SortableResult id) throws Exception {
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
    
    results.close();
    
    return resultCount;
  }
  
  public long columnFetch(SortableResult id, Column colToFetch) throws Exception {
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
    
    results.close();
    
    return resultCount;
  }
  
  public void groupBy(SortableResult id, Column colToFetch) throws Exception {
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
    ExecutorService runner = Executors.newFixedThreadPool(3);
    for (int i = 0; i < 1; i++) {
      runner.execute(runQueries(2));
    }
    
    runner.shutdown();
    runner.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
  }
}
