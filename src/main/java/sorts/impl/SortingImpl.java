package sorts.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.BatchDeleter;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sorts.Sorting;
import sorts.SortingMetadata;
import sorts.SortingMetadata.State;
import sorts.UnexpectedStateException;
import sorts.UnindexedColumnException;
import sorts.accumulo.GroupByRowSuffixIterator;
import sorts.options.Index;
import sorts.options.Order;
import sorts.options.Paging;
import sorts.results.CloseableIterable;
import sorts.results.Column;
import sorts.results.PagedQueryResult;
import sorts.results.QueryResult;
import sorts.results.SValue;
import sorts.results.impl.MultimapQueryResult;
import sorts.util.IndexHelper;
import sorts.util.Single;

import com.google.common.collect.Iterables;
import com.google.common.io.Closeables;

public class SortingImpl implements Sorting {
  private static final Logger log = LoggerFactory.getLogger(SortingImpl.class);
  
  public static final String NULL_BYTE_STR = "\0";
  public static final String DOCID_FIELD_NAME = "SORTS_DOCID";
  public static final Text DOCID_FIELD_NAME_TEXT = new Text(DOCID_FIELD_NAME);
  public static final String FORWARD = "f";
  public static final String REVERSE = "r";
  public static final Value EMPTY_VALUE = new Value(new byte[0]);
  public static final String CURATOR_PREFIX = "/sorts/";
  
  public static final long LOCK_SECS = 10;
  
  private final BatchWriterConfig DEFAULT_BW_CONFIG = new BatchWriterConfig();
  private final CuratorFramework curator;
  
  public SortingImpl(String zookeepers) {
    RetryPolicy retryPolicy = new ExponentialBackoffRetry(2000, 3);
    curator = CuratorFrameworkFactory.newClient(zookeepers, retryPolicy);
    curator.start();
    
    // TODO http://curator.incubator.apache.org/curator-recipes/shared-reentrant-lock.html
    // "Error handling: ... strongly recommended that you add a ConnectionStateListener and
    // watch for SUSPENDED and LOST state changes"
    
    // curator.getConnectionStateListenable().addListener(new ConnectionStateListener() {
    //
    // @Override
    // public void stateChanged(CuratorFramework client, ConnectionState newState) {
    //
    // }
    //
    // });
  }
  
  @Override
  public void close() {
    synchronized (curator) {
      CuratorFrameworkState state = curator.getState();
      
      // Stop unless we're already stopped
      if (!CuratorFrameworkState.STOPPED.equals(state)) {
        try {
          Closeables.close(curator, true);
        } catch (IOException e) {
          log.warn("Caught IOException closing Curator connection", e);
        }
      }
    }
  }
  
  /**
   * finalize is not guaranteed to be called, as such, care should be taken to ensure that {@link close} is called.
   */
  @Override
  public void finalize() throws IOException {
    this.close();
  }
  
  @Override
  public void register(SortableResult id) throws TableNotFoundException, MutationsRejectedException, UnexpectedStateException {
    checkNotNull(id);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.UNKNOWN.equals(s)) {
      UnexpectedStateException e = unexpectedState(id, State.UNKNOWN, s);
      log.error(e.getMessage());
      throw e;
    }
    
    State targetState = State.LOADING;
    
    log.debug("Setting state for {} from {} to {}", new Object[] {id, s, targetState});
    
    SortingMetadata.setState(id, targetState);
  }
  
  @Override
  public void addResult(SortableResult id, QueryResult<?> queryResult) throws Exception {
    checkNotNull(queryResult);
    
    addResults(id, Single.<QueryResult<?>> create(queryResult));
  }
  
  @Override
  public void addResults(SortableResult id, Iterable<? extends QueryResult<?>> queryResults) throws Exception {
    checkNotNull(id);
    checkNotNull(queryResults);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s)) {
      UnexpectedStateException e = unexpectedState(id, State.LOADING, s);
      log.error(e.getMessage());
      throw e;
    }
    
    InterProcessMutex lock = getMutex(id);
    
    // TODO We don't need to lock on multiple calls to addResults; however, we need to lock over adding the
    // new records to make sure a call to index() doesn't come in while we're processing a stale set of Columns to index
    boolean locked = false;
    int count = 1;
    
    if (id.lockOnUpdates()) {
      while (!locked && count < 4) {
        if (locked = lock.acquire(10, TimeUnit.SECONDS)) {
          try {
            performAdd(id, queryResults);
          } finally {
            // Don't hog the lock
            lock.release();
          }
        } else {
          count++;
          log.warn("addResults() on {} could not acquire lock after {} seconds. Attempting acquire #{}", new Object[] {id.uuid(), LOCK_SECS, count});
          
        }
      }
    } else {
      performAdd(id, queryResults);
    }
  }
  
  protected void performAdd(SortableResult id, Iterable<? extends QueryResult<?>> queryResults) throws MutationsRejectedException, TableNotFoundException, IOException {
    BatchWriter bw = null, metadataBw = null;
    
    try {
      // Add the values of columns to the sortableresult as we want
      Set<Index> columnsToIndex = id.columnsToIndex();
      
      bw = id.connector().createBatchWriter(id.dataTable(), DEFAULT_BW_CONFIG);
      metadataBw = id.connector().createBatchWriter(id.metadataTable(), DEFAULT_BW_CONFIG);
      
      final IndexHelper indexHelper = IndexHelper.create(columnsToIndex);
      final Text holder = new Text();
      
      for (QueryResult<?> result : queryResults) {
        bw.addMutation(addDocument(id, result));
        Mutation columnMutation = new Mutation(id.uuid());
        
        for (Entry<Column,SValue> entry : result.columnValues()) {
          final Column c = entry.getKey();
          final SValue v = entry.getValue();
          holder.set(c.column());
          
          columnMutation.put(SortingMetadata.COLUMN_COLFAM, holder, EMPTY_VALUE);
          
          if (indexHelper.shouldIndex(c)) {
            for (Index index : indexHelper.indicesForColumn(c)) {
              Mutation m = getDocumentPrefix(id, result, v.value());
              
              final String direction = Order.ASCENDING.equals(index.order()) ? FORWARD : REVERSE;
              m.put(index.column().toString(), direction + NULL_BYTE_STR + result.docId(), result.documentVisibility(), result.toValue());
              
              bw.addMutation(m);
            }
          }
        }
        
        metadataBw.addMutation(columnMutation);
      }
    } catch (MutationsRejectedException e) {
      log.error("Caught exception adding results for {}", id, e);
      throw e;
    } catch (TableNotFoundException e) {
      log.error("Caught exception adding results for {}", id, e);
      throw e;
    } catch (RuntimeException e) {
      log.error("Caught exception adding results for {}", id, e);
      throw e;
    } catch (IOException e) {
      log.error("Caught exception adding results for {}", id, e);
      throw e;
    } finally {
      if (null != bw) {
        bw.close();
      }
      if (null != metadataBw) {
        metadataBw.close();
      }
    }
  }
  
  @Override
  public void finalize(SortableResult id) throws TableNotFoundException, MutationsRejectedException, UnexpectedStateException {
    checkNotNull(id);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s)) {
      throw unexpectedState(id, State.LOADING, s);
    }
    
    final State desiredState = State.LOADED;
    
    log.debug("Changing state for {} from {} to {}", new Object[] {id, s, desiredState});
    
    SortingMetadata.setState(id, desiredState);
  }
  
  @Override
  public void index(SortableResult id, Set<Index> columnsToIndex) throws Exception {
    checkNotNull(id);
    checkNotNull(columnsToIndex);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    InterProcessMutex lock = getMutex(id);
    
    boolean locked = false;
    int count = 1;
    
    if (id.lockOnUpdates) {
      while (!locked && count < 4) {
        if (locked = lock.acquire(10, TimeUnit.SECONDS)) {
          try {
            performUpdate(id, columnsToIndex);
            
          } finally {          
            lock.release();
          }
          
          return;
        } else {
          count++;
          log.warn("index() on {} could not acquire lock after {} seconds. Attempting acquire #{}", new Object[] {id.uuid(), LOCK_SECS, count});
        }
      }
    } else {
      performUpdate(id, columnsToIndex);
    }
    
    throw new IllegalStateException("Could not acquire lock during index() after " + count + " attempts");
  }
  
  protected void performUpdate(SortableResult id, Set<Index> columnsToIndex) throws TableNotFoundException, UnexpectedStateException, MutationsRejectedException, IOException {
    final IndexHelper indexHelper = IndexHelper.create(columnsToIndex);
    final int numCols = indexHelper.columnCount();
    CloseableIterable<MultimapQueryResult> results = null;
    BatchWriter bw = null;
    
    try {
      // Add the values of columns to the sortableresult as we want future results to be indexed the same way
      id.addColumnsToIndex(columnsToIndex);
      
      // Get the results we have to update
      results = fetch(id);
      
      bw = id.connector().createBatchWriter(id.dataTable(), DEFAULT_BW_CONFIG);
      
      // Iterate over the results we have
      for (MultimapQueryResult result : results) {
        
        // If the cardinality of columns is greater in this result than the number of columns
        // we want to index
        if (result.columnSize() > numCols) {
          // It's more efficient to go over each column to index
          for (Column columnToIndex : indexHelper.columnIndices().keySet()) {
            
            // Determine if the object contains the column we need to index
            if (result.containsKey(columnToIndex)) {
              // If so, get the value(s) for that column
              final Collection<Index> indices = indexHelper.indicesForColumn(columnToIndex);
              final Collection<SValue> values = result.get(columnToIndex);
              
              addIndicesForRecord(id, result, bw, indices, values);
            }
          }
        } else {
          // Otherwise it's more efficient to iterate over the columns of the result
          for (Entry<Column,SValue> entry : result.columnValues()) {
            final Column column = entry.getKey();
            
            // Determine if we should index this column
            if (indexHelper.shouldIndex(column)) {
              final Collection<Index> indices = indexHelper.indicesForColumn(column);
              final Collection<SValue> values = result.get(column);
              
              addIndicesForRecord(id, result, bw, indices, values);
            }
          }
        }
      }
    } finally {
      if (null != bw) {
        bw.close();
      }
      if (null != results) {
        results.close();
      }
    }
  }
  
  /**
   * For a QueryResult, write the Index(es) for the Column the SValues came from.
   * 
   * @param id
   * @param result
   * @param bw
   * @param indices
   * @param values
   * @throws MutationsRejectedException
   * @throws IOException
   */
  protected void addIndicesForRecord(SortableResult id, MultimapQueryResult result, BatchWriter bw, Collection<Index> indices, Collection<SValue> values)
      throws MutationsRejectedException, IOException {
    for (SValue value : values) {
      Mutation m = getDocumentPrefix(id, result, value.value());
      
      // Place an Index entry for each value in each direction defined
      for (Index index : indices) {
        final String direction = Order.ASCENDING.equals(index.order()) ? FORWARD : REVERSE;
        m.put(index.column().toString(), direction + NULL_BYTE_STR + result.docId(), result.documentVisibility(), result.toValue());
      }
      
      bw.addMutation(m);
    }
  }
  
  @Override
  public Iterable<Column> columns(SortableResult id) throws TableNotFoundException, UnexpectedStateException {
    checkNotNull(id);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    return SortingMetadata.columns(id);
  }
  
  @Override
  public CloseableIterable<MultimapQueryResult> fetch(SortableResult id) throws TableNotFoundException, UnexpectedStateException {
    checkNotNull(id);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    BatchScanner bs = id.connector().createBatchScanner(id.dataTable(), id.auths(), 10);
    bs.setRanges(Collections.singleton(Range.prefix(id.uuid())));
    bs.fetchColumnFamily(DOCID_FIELD_NAME_TEXT);
    
    return CloseableIterable.create(bs, Iterables.transform(bs, new KVToMultimap()));
  }
  
  @Override
  public PagedQueryResult<MultimapQueryResult> fetch(SortableResult id, Paging limits) throws TableNotFoundException, UnexpectedStateException {
    checkNotNull(id);
    checkNotNull(limits);
    
    CloseableIterable<MultimapQueryResult> results = fetch(id);
    
    return new PagedQueryResult<MultimapQueryResult>(results, limits);
  }
  
  @Override
  public CloseableIterable<MultimapQueryResult> fetch(SortableResult id, Column column, String value) throws TableNotFoundException, UnexpectedStateException {
    checkNotNull(id);
    checkNotNull(column);
    checkNotNull(value);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    BatchScanner bs = id.connector().createBatchScanner(id.dataTable(), id.auths(), 10);
    bs.setRanges(Collections.singleton(Range.exact(id.uuid() + NULL_BYTE_STR + value)));
    bs.fetchColumnFamily(new Text(column.column()));
    
    return CloseableIterable.transform(bs, new KVToMultimap());
  }
  
  @Override
  public PagedQueryResult<MultimapQueryResult> fetch(SortableResult id, Column column, String value, Paging limits) throws TableNotFoundException,
      UnexpectedStateException {
    checkNotNull(limits);
    
    CloseableIterable<MultimapQueryResult> results = fetch(id, column, value);
    
    return PagedQueryResult.create(results, limits);
  }
  
  @Override
  public CloseableIterable<MultimapQueryResult> fetch(SortableResult id, Index ordering) throws TableNotFoundException, UnexpectedStateException,
      UnindexedColumnException {
    return fetch(id, ordering, true);
  }
  
  @Override
  public CloseableIterable<MultimapQueryResult> fetch(SortableResult id, Index ordering, boolean duplicateUidsAllowed) throws TableNotFoundException,
      UnexpectedStateException, UnindexedColumnException {
    checkNotNull(id);
    checkNotNull(ordering);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    Index.define(ordering.column());
    
    if (!id.columnsToIndex().contains(ordering)) {
      log.error("{} is not indexed by {}", ordering, id);
      throw new UnindexedColumnException();
    }
  
    // TODO seriously? batchscanner when order is important?
    BatchScanner bs = id.connector().createBatchScanner(id.dataTable(), id.auths(), 10);
    bs.setRanges(Collections.singleton(Range.prefix(id.uuid())));
    bs.fetchColumnFamily(new Text(ordering.column().column()));
    
    // TODO Also need to adhere to the Ordering on the Index
    
    // If the client has told us they don't want duplicate records, lets not give them duplicate records
    if (duplicateUidsAllowed) {
      return CloseableIterable.transform(bs, new KVToMultimap());
    } else {
      return CloseableIterable.filterAndTransform(bs, new DedupingPredicate(), new KVToMultimap());
    }
  }
  
  @Override
  public PagedQueryResult<MultimapQueryResult> fetch(SortableResult id, Index ordering, Paging limits) throws TableNotFoundException, UnexpectedStateException,
      UnindexedColumnException {
    checkNotNull(id);
    checkNotNull(limits);
    
    CloseableIterable<MultimapQueryResult> results = fetch(id, ordering);
    
    return PagedQueryResult.create(results, limits);
  }
  
  @Override
  public CloseableIterable<Entry<SValue,Long>> groupResults(SortableResult id, Column column) throws TableNotFoundException, UnexpectedStateException,
      UnindexedColumnException {
    checkNotNull(id);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    checkNotNull(column);
    
    Text colf = new Text(column.column());
    
    BatchScanner bs = id.connector().createBatchScanner(id.dataTable(), id.auths(), 10);
    bs.setRanges(Collections.singleton(Range.prefix(id.uuid())));
    bs.fetchColumnFamily(colf);
    
    IteratorSetting cfg = new IteratorSetting(50, GroupByRowSuffixIterator.class);
    bs.addScanIterator(cfg);
    
    return CloseableIterable.transform(bs, new GroupByFunction());
  }
  
  @Override
  public PagedQueryResult<Entry<SValue,Long>> groupResults(SortableResult id, Column column, Paging limits) throws TableNotFoundException,
      UnexpectedStateException, UnindexedColumnException {
    checkNotNull(limits);
    
    CloseableIterable<Entry<SValue,Long>> results = groupResults(id, column);
    
    return PagedQueryResult.create(results, limits);
  }
  
  @Override
  public void delete(SortableResult id) throws TableNotFoundException, MutationsRejectedException, UnexpectedStateException {
    checkNotNull(id);
    
    State s = SortingMetadata.getState(id);
    
    if (!State.LOADING.equals(s) && !State.LOADED.equals(s)) {
      throw unexpectedState(id, new State[] {State.LOADING, State.LOADED}, s);
    }
    
    final State desiredState = State.DELETING;
    
    log.debug("Changing state for {} from {} to {}", new Object[] {id, s, desiredState});
    
    SortingMetadata.setState(id, desiredState);
    
    // Delete of the Keys
    BatchDeleter bd = null;
    try {
      bd = id.connector().createBatchDeleter(id.dataTable(), id.auths(), 4, new BatchWriterConfig());
      bd.setRanges(Collections.singleton(Range.prefix(id.uuid())));
      
      bd.delete();
    } finally {
      if (null != bd) {
        bd.close();
      }
    }
    
    log.debug("Removing state for {}", id);
    
    SortingMetadata.remove(id);
  }
  
  protected Mutation getDocumentPrefix(SortableResult id, QueryResult<?> queryResult, String suffix) {
    return new Mutation(id.uuid() + NULL_BYTE_STR + suffix);
  }
  
  protected Mutation addDocument(SortableResult id, QueryResult<?> queryResult) throws IOException {
    Mutation m = getDocumentPrefix(id, queryResult, queryResult.docId());
    
    // TODO be more space efficient here and store a reference to the document once in Accumulo
    // merits: don't bloat the default locality group's index, less size overall
    m.put(DOCID_FIELD_NAME, FORWARD + NULL_BYTE_STR + queryResult.docId(), queryResult.documentVisibility(), queryResult.toValue());
    
    return m;
  }
  
  protected UnexpectedStateException unexpectedState(SortableResult id, State[] expected, State actual) {
    return new UnexpectedStateException("Invalid state " + id + " for " + id + ". Expected one of " + Arrays.asList(expected) + " but was " + actual);
  }
  
  protected UnexpectedStateException unexpectedState(SortableResult id, State expected, State actual) {
    return new UnexpectedStateException("Invalid state " + id + " for " + id + ". Expected " + expected + " but was " + actual);
  }
  
  protected final InterProcessMutex getMutex(SortableResult id) {
    return new InterProcessMutex(curator, SortingImpl.CURATOR_PREFIX + id.uuid());
  }
}
